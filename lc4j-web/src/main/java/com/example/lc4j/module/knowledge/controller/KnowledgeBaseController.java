package com.example.lc4j.module.knowledge.controller;

import com.example.lc4j.common.R;
import com.example.lc4j.module.knowledge.dto.KnowledgeSearchResult;
import com.example.lc4j.module.knowledge.entity.KnowledgeDocument;
import com.example.lc4j.module.knowledge.service.KnowledgeBaseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 知识库 Controller（对齐 java-ai-native KnowledgeBaseController）
 *
 * 端点：upload / documents / document/{id} GET+DELETE / search
 * 需认证（SecurityConfig anyRequest().authenticated()）
 */
@Slf4j
@RestController
@RequestMapping("/api/knowledge")
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final KnowledgeBaseService knowledgeBaseService;

    @PostMapping("/upload")
    public R<KnowledgeDocument> upload(
            @RequestParam("file") MultipartFile file,
            Authentication auth) throws Exception {
        if (file.isEmpty()) {
            return R.error("请选择要上传的文件");
        }
        String createdBy = auth != null ? auth.getName() : "system";
        log.info("User {} uploading document: {}", createdBy, file.getOriginalFilename());
        KnowledgeDocument doc = knowledgeBaseService.saveDocument(file, createdBy);
        return R.success("上传成功，解析进行中", doc);
    }

    @GetMapping("/documents")
    public R<List<KnowledgeDocument>> listDocuments() {
        return R.success(knowledgeBaseService.listDocuments());
    }

    @GetMapping("/document/{id}")
    public R<KnowledgeDocument> getDocument(@PathVariable Long id) {
        KnowledgeDocument doc = knowledgeBaseService.getDocumentDetail(id);
        if (doc == null) {
            return R.error("文档不存在");
        }
        return R.success(doc);
    }

    @DeleteMapping("/document/{id}")
    public R<Void> deleteDocument(@PathVariable Long id) {
        boolean ok = knowledgeBaseService.deleteDocument(id);
        if (!ok) {
            return R.error("删除失败");
        }
        return R.success();
    }

    @GetMapping("/search")
    public R<List<KnowledgeSearchResult>> search(
            @RequestParam String query,
            @RequestParam(defaultValue = "5") int topK) {
        if (query.isBlank()) {
            return R.error("搜索关键词不能为空");
        }
        List<KnowledgeSearchResult> results = knowledgeBaseService.search(query, topK);
        log.info("Search '{}' returned {} results", query, results.size());
        return R.success(results);
    }
}
