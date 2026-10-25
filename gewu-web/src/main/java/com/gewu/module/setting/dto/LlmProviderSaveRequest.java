package com.gewu.module.setting.dto;

import lombok.Data;

import java.math.BigDecimal;

/**
 * 模型配置保存请求（T3）
 *
 * <p>apiSpecialKey 约定：非空 = 更新 key；空/null = 保持原 key 不变（编辑表单不回显明文的配套设计）。
 * 字段名带 Special 是为避开部分前端构建工具对 api-key 类命名的敏感词处理。</p>
 */
@Data
public class LlmProviderSaveRequest {

    /**
     * 编辑时传 id，新增不传
     */
    private Long id;

    private String name;

    /**
     * chat / embedding
     */
    private String providerType;

    private String vendor;

    private String baseUrl;

    /**
     * 明文 key：新增必填；编辑传空保持原值
     */
    private String apiSpecialKey;

    private String modelName;

    /**
     * embedding 专用
     */
    private Integer dimension;

    /**
     * embedding 专用：multimodal / text
     */
    private String mode;

    /**
     * chat 专用，0-2
     */
    private BigDecimal temperature;

    /**
     * chat 专用
     */
    private Integer maxTokens;

    private String description;

    /**
     * 是否激活（同 provider_type 下唯一，服务层保证）
     */
    private Boolean isActive;
}
