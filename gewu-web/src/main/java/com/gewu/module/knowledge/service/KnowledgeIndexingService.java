package com.gewu.module.knowledge.service;

import com.gewu.client.VolcMultiModalEmbeddingModel;
import com.gewu.module.knowledge.entity.DocumentChunk;
import com.gewu.module.knowledge.entity.KnowledgeDocument;
import com.gewu.module.knowledge.mapper.DocumentChunkMapper;
import com.gewu.module.knowledge.mapper.KnowledgeDocumentMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * 知识库后台索引服务（对齐 java-ai-native KnowledgeIndexingService）
 *
 * 独立成类是为了让 @Async 通过 Spring 代理生效
 * （同类内直接调用异步方法会短路走本地栈，拿不到代理）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeIndexingService {

    private static final int EMBEDDING_BATCH = 5;

    private final DocumentParseService parseService;
    private final VolcMultiModalEmbeddingModel embeddingModel;
    private final DocumentChunkMapper chunkMapper;
    private final KnowledgeDocumentMapper documentMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 后台处理：解析 → 分块 → embed → 插 chunk → 更新 status。
     *
     * 不加类级 @Transactional——异步事务过长会长时间持连接；
     * 状态推进按需短事务或单条 update 即可。
     * 所有异常内部兜住，不向上抛（异步异常不会传播到调用方）。
     */
    @Async("knowledgeExecutor")
    public void processDocumentAsync(Long docId) {
        KnowledgeDocument doc = documentMapper.selectById(docId);
        if (doc == null) {
            log.warn("processDocumentAsync: document not found, id={}", docId);
            return;
        }

        String fileName = doc.getFileName();
        Path filePath = Paths.get(doc.getFilePath());

        try {
            log.info("[async] parsing document: {}", fileName);
            String text = parseService.extractText(filePath);
            doc.setContent(text);

            log.info("[async] chunking document: {}", fileName);
            List<String> chunks = parseService.chunkText(text, doc.getFileSize());
            doc.setChunkCount(chunks.size());
            documentMapper.updateById(doc);

            log.info("[async] embedding {} chunks", chunks.size());
            List<DocumentChunk> chunkEntities = new ArrayList<>();

            for (int i = 0; i < chunks.size(); i += EMBEDDING_BATCH) {
                int end = Math.min(i + EMBEDDING_BATCH, chunks.size());
                List<String> batch = chunks.subList(i, end);
                log.info("[async] embedding batch {}/{} (size={})",
                        i / EMBEDDING_BATCH + 1,
                        (chunks.size() + EMBEDDING_BATCH - 1) / EMBEDDING_BATCH,
                        batch.size());

                // LangChain4j 接口批量向量化（multimodal 模式内部逐条串行，text 模式真批量）
                List<dev.langchain4j.data.embedding.Embedding> embeddings =
                        embeddingModel.embedAll(toSegments(batch)).content();

                for (int j = 0; j < batch.size(); j++) {
                    DocumentChunk chunk = new DocumentChunk();
                    chunk.setDocumentId(doc.getId());
                    chunk.setChunkIndex(i + j);
                    chunk.setContent(batch.get(j));
                    float[] vec = embeddings.get(j).vector();
                    chunk.setDimension(vec.length);
                    chunk.setEmbedding(toVectorLiteral(vec));
                    chunkEntities.add(chunk);
                }
            }

            for (DocumentChunk chunk : chunkEntities) {
                chunkMapper.insertChunk(chunk);
            }

            doc.setStatus(2);
            documentMapper.updateById(doc);
            log.info("[async] document processed: {}, chunks: {}", fileName, chunks.size());

        } catch (Exception e) {
            log.error("[async] document processing failed: {}", fileName, e);
            markFailed(docId, e.getMessage());
        }
    }

    private List<dev.langchain4j.data.segment.TextSegment> toSegments(List<String> texts) {
        List<dev.langchain4j.data.segment.TextSegment> segments = new ArrayList<>(texts.size());
        for (String text : texts) {
            segments.add(dev.langchain4j.data.segment.TextSegment.from(text));
        }
        return segments;
    }

    /**
     * float[] → pgvector 文本形式 "[0.1,-0.2,...]"
     */
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

    private void markFailed(Long docId, String message) {
        try {
            KnowledgeDocument fresh = documentMapper.selectById(docId);
            if (fresh == null) {
                return;
            }
            fresh.setStatus(3);
            String msg = message == null ? "unknown error" : message;
            if (msg.length() > 500) {
                msg = msg.substring(0, 500);
            }
            fresh.setErrorMsg(msg);
            documentMapper.updateById(fresh);
        } catch (Exception ex) {
            log.error("[async] failed to mark document as failed, id={}", docId, ex);
        }
    }
}
