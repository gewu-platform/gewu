package com.gewu.infrastructure.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 登录防暴力破解配置（对齐 java-ai-native security.auth.throttle 配置形态）
 * native 仅留配置未实现，此处补齐实现
 */
@Data
@Component
@ConfigurationProperties(prefix = "security.auth.throttle")
public class AuthThrottleProperties {

    /**
     * 是否启用登录限流
     */
    private boolean enabled = true;

    /**
     * 窗口内最大尝试次数
     */
    private int maxAttempts = 5;

    /**
     * 限流窗口（秒）
     */
    private long windowSeconds = 300;
}
