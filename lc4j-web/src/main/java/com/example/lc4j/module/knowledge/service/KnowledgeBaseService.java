package com.example.lc4j.module.knowledge.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.lc4j.client.VolcMultiModalEmbeddingModel;
import com.example.lc4j.module.knowledge.dto.KnowledgeSearchResult;
import com.example.lc4j.module.knowledge.entity.DocumentChunk;
import com.example.lc4j.module.knowledge.entity.KnowledgeDocument;
import com.example.lc4j.module.knowledge.mapper.DocumentChunkMapper;
import com.example.lc4j.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * 知识库业务服务（对齐 java-ai-native KnowledgeBaseService）
 *
 * 上传：同步落库+落盘，事务提交后触发后台异步解析/embedding；
 * 检索：查询向量化 → pgvector 余弦相似度 Top-K；
 * 删除：chunk 物理删除 + 文档软删 + 物理文件删除。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeBaseService extends ServiceImpl<KnowledgeDocumentMapper, KnowledgeDocument> {

    private final VolcMultiModalEmbeddingModel embeddingModel;
    private final DocumentChunkMapper chunkMapper;
    private final KnowledgeIndexingService indexingService;

    private static final String UPLOAD_DIR = System.getProperty("user.dir") + "/uploads/knowledge/";
    private static final int TOP_K = 5;

    /**
     * 相似度阈值（可配置）：multimodal 融合模型对「问题 vs 陈述文」的余弦相似度
     * 普遍低于 text 模型，0.6 会滤掉有效结果；默认 0.3，yml 可调。
     */
    @Value("${langchain4j.knowledge.similarity-threshold:0.3}")
    private double similarityThreshold;

    /**
     * 同步落库 + 落盘，并触发后台解析/embedding。
     *
     * 返回时 doc.status = 1（处理中），前端凭 id 轮询 {@link #getDocumentDetail} 拿最终状态：
     * 2 = 完成，3 = 失败（错误信息见 errorMsg）。
     */
    @Transactional
    public KnowledgeDocument saveDocument(MultipartFile file, String createdBy) throws IOException {
        String fileName = file.getOriginalFilename();
        String ext = getFileExtension(fileName);

        if (!List.of("pdf", "docx", "txt", "md").contains(ext.toLowerCase())) {
            throw new IllegalArgumentException("Unsupported file type: " + ext);
        }

        Path uploadPath = Paths.get(UPLOAD_DIR);
        if (!Files.exists(uploadPath)) {
            Files.createDirectories(uploadPath);
        }

        String savedName = System.currentTimeMillis() + "_" + fileName;
        Path savedPath = uploadPath.resolve(savedName);
        file.transferTo(savedPath);

        KnowledgeDocument doc = new KnowledgeDocument();
        doc.setFileName(fileName);
        doc.setFileType(ext.toLowerCase());
        doc.setFileSize(file.getSize());
        doc.setStatus(1);
        doc.setCreatedBy(createdBy != null ? createdBy : "system");
        doc.setFilePath(savedPath.toString());
        doc.setCreateTime(java.time.LocalDateTime.now());
        doc.setUpdateTime(java.time.LocalDateTime.now());

        this.save(doc);

        // 等事务提交后再触发异步处理
        // 否则异步线程在当前事务未提交时 selectById 会查不到数据（PostgreSQL 读已提交隔离级别）
        final Long docId = doc.getId();
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                indexingService.processDocumentAsync(docId);
            }
        });

        return doc;
    }

    public List<KnowledgeSearchResult> search(String query, int topK) {
        log.info("Knowledge search: {}", query);
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String queryVecLiteral = toVectorLiteral(queryEmbedding.vector());
        int limit = topK > 0 ? topK : TOP_K;
        List<KnowledgeSearchResult> results =
                chunkMapper.searchSimilar(queryVecLiteral, similarityThreshold, limit);
        if (results.isEmpty()) {
            // 无命中时记录最高相似度，便于校准阈值
            List<KnowledgeSearchResult> probe =
                    chunkMapper.searchSimilar(queryVecLiteral, 0.0, 1);
            if (!probe.isEmpty()) {
                log.warn("Search '{}' 0 hits at threshold {}, best similarity = {}",
                        query, similarityThreshold, probe.get(0).getSimilarity());
            }
        }
        return results;
    }

    /**
     * P8b：限定单文档内的相似度检索（编排层 RAG 注入用）
     * 复用 search 的嵌入逻辑，SQL 层加 document_id 过滤，阈值放宽至配置值的 60%
     * （限库内检索命中面更小，阈值过严会导致编排层无米下锅）
     */
    public List<KnowledgeSearchResult> searchByDocument(String query, Long documentId, int topK) {
        log.info("Knowledge search in doc {}: {}", documentId, query);
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        String queryVecLiteral = toVectorLiteral(queryEmbedding.vector());
        double threshold = Math.max(0.1, similarityThreshold * 0.6);
        List<KnowledgeSearchResult> results =
                chunkMapper.searchSimilarInDocument(queryVecLiteral, documentId, threshold, topK);
        log.info("Doc {} search hits: {}", documentId, results.size());
        return results;
    }
    @Transactional
    public boolean deleteDocument(Long documentId) {
        // 1. 先获取文档路径（用于物理删除）
        KnowledgeDocument doc = this.getById(documentId);
        if (doc == null) {
            return false;
        }

        // 2. 删除关联 chunk 记录（物理删除，向量同步清除）
        chunkMapper.deleteByDocumentId(documentId);

        // 3. 软删除文档记录（@TableLogic 自动标记 deleted=1）
        boolean dbOk = this.removeById(documentId);

        // 4. 物理删除上传的文件
        if (doc.getFilePath() != null) {
            try {
                Path filePath = Paths.get(doc.getFilePath());
                if (Files.exists(filePath)) {
                    Files.delete(filePath);
                    log.info("Deleted physical file: {}", filePath);
                }
            } catch (IOException e) {
                log.warn("Failed to delete physical file for document {}: {}", documentId, e.getMessage());
            }
        }

        return dbOk;
    }

    public KnowledgeDocument getDocumentDetail(Long documentId) {
        return this.getById(documentId);
    }

    public List<KnowledgeDocument> listDocuments() {
        return this.list(new LambdaQueryWrapper<KnowledgeDocument>()
                .eq(KnowledgeDocument::getDeleted, 0)
                .orderByDesc(KnowledgeDocument::getCreateTime));
    }

    private String toVectorLiteral(float[] vec) {
        StringBuilder sb = new StringBuilder(vec.length * 10);
        sb.append('[');
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vec[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    private String getFileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot + 1) : "";
    }
}
