package com.gewu.module.knowledge.dto;

import lombok.Data;

/**
 * 知识检索结果 DTO（对齐 java-ai-native KnowledgeSearchResult）
 */
@Data
public class KnowledgeSearchResult {

    /**
     * 分块 ID
     */
    private Long chunkId;

    /**
     * 所属文档 ID
     */
    private Long documentId;

    /**
     * 文档名称
     */
    private String documentName;

    /**
     * 分块内容
     */
    private String content;

    /**
     * 分块序号
     */
    private Integer chunkIndex;

    /**
     * 相似度（余弦，1 - 距离）
     */
    private Double similarity;
}
