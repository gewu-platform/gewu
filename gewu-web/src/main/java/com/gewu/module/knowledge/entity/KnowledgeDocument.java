package com.gewu.module.knowledge.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.FieldStrategy;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档实体（对齐 java-ai-native KnowledgeDocument）
 *
 * 对应表：knowledge_document（PostgreSQL）
 */
@Data
@TableName("knowledge_document")
public class KnowledgeDocument {

    /**
     * 文档 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 原始文件名
     */
    private String fileName;

    /**
     * 物理存储路径
     */
    private String filePath;

    /**
     * 文件类型 - pdf / docx / txt / md
     */
    private String fileType;

    /**
     * 文件大小（字节）
     */
    private Long fileSize;

    /**
     * 解析后的全文（不入库，仅查询后透传）
     */
    @TableField(insertStrategy = FieldStrategy.NEVER, updateStrategy = FieldStrategy.NEVER)
    private String content;

    /**
     * 分块数量
     */
    private Integer chunkCount;

    /**
     * 状态 - 0:待处理 / 1:处理中 / 2:已完成 / 3:失败
     */
    private Integer status;

    /**
     * 上传人
     */
    private String createdBy;

    /**
     * 失败原因（status=3 时）
     */
    private String errorMsg;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;

    /**
     * 逻辑删除 - 0:正常 / 1:已删除
     */
    @TableLogic
    private Integer deleted;
}
