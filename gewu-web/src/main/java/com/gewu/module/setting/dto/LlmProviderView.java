package com.gewu.module.setting.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 模型配置列表/详情响应（T3）
 *
 * <p>安全约定：绝不返回 apiKeyEnc 密文，更无明文；hasApiKey 供前端判断「已配置」状态。</p>
 */
@Data
public class LlmProviderView {

    private Long id;

    private String name;

    private String providerType;

    private String vendor;

    private String baseUrl;

    /**
     * 密钥掩码（如 sk-****last4），仅展示用途
     */
    private String apiKeyMasked;

    /**
     * 是否已配置密钥
     */
    private Boolean hasApiKey;

    private String modelName;

    private Integer dimension;

    private String mode;

    private BigDecimal temperature;

    private Integer maxTokens;

    private String description;

    private Boolean isActive;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
