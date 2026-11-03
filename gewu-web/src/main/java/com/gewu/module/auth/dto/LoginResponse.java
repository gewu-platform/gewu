package com.gewu.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.gewu.module.user.dto.UserInfoView;

/**
 * 登录响应 DTO（对齐 java-ai-native LoginResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    private String token;

    private String refreshToken;

    private UserInfoView user;
}
