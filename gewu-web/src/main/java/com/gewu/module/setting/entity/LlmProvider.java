package com.gewu.module.setting.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型服务配置实体（T3 模型配置中心）
 *
 * <p>chat / embedding 两类模型服务的统一配置源。chat 侧 name 同时作为
 * {@code LangChain4jConfig.models} 的模型 ID（激活行覆盖 yml activeModel）。</p>
 *
 * <p>时间字段由表默认值维护（本项目无 MetaObjectHandler，与既有表保持一致）。</p>
 */
@Data
@TableName("llm_provider")
public class LlmProvider implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 配置名称（唯一；chat 侧 = LangChain4jConfig 模型 ID）
     */
    private String name;

    /**
     * 配置类型 - chat:对话模型 / embedding:向量模型
     */
    private String providerType;

    /**
     * 服务商 - volcengine/aliyun/openai（OpenAI 兼容端点）
     */
    private String vendor;

    /**
     * API Base URL
     */
    private String baseUrl;

    /**
     * API Key 密文（AES-256-GCM，v1:iv:ct 格式，接口层永不回显明文）
     */
    private String apiKeyEnc;

    /**
     * 模型名称（火山为端点 ID，如 ep-xxx）
     */
    private String modelName;

    /**
     * 向量维度（embedding 专用）
     */
    private Integer dimension;

    /**
     * embedding 模式 - multimodal:多模态逐条串行 / text:标准批量
     */
    private String mode;

    /**
     * 随机性 0-2（chat 专用）
     */
    private BigDecimal temperature;

    /**
     * 最大生成 token 数（chat 专用）
     */
    private Integer maxTokens;

    /**
     * 描述（用于前端展示）
     */
    private String description;

    /**
     * 是否激活 - 同 provider_type 下至多一条 TRUE（uk_llm_provider_active_type 部分唯一索引兜底）
     */
    private Boolean isActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
