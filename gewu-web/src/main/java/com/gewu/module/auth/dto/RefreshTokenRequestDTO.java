package com.gewu.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 刷新令牌请求 DTO（对齐 java-ai-native）
 */
@Data
public class RefreshTokenRequestDTO {
    @NotBlank(message = "刷新令牌不能为空")
    private String refreshToken;
}
