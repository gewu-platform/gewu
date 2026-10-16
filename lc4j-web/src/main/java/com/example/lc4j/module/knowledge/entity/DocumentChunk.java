package com.example.lc4j.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 文档分块实体（对齐 java-ai-native DocumentChunk）
 *
 * 对应表：document_chunk（PostgreSQL，embedding 为 pgvector vector(2048)）
 * 注意：embedding 在实体中为 String（pgvector 文本形式 "[0.1,-0.2,...]"），
 * 插入/检索时由自定义 SQL 显式 CAST(... AS vector)
 */
@Data
@TableName("document_chunk")
public class DocumentChunk {

    /**
     * 分块 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 所属文档 ID
     */
    private Long documentId;

    /**
     * 分块文本内容
     */
    private String content;

    /**
     * 分块序号（从 0 开始）
     */
    private Integer chunkIndex;

    /**
     * 向量（pgvector 文本形式，插入时 CAST AS vector）
     */
    private String embedding;

    /**
     * 向量维度
     */
    private Integer dimension;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
