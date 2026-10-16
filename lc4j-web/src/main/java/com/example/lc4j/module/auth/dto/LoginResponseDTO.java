package com.example.lc4j.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.example.lc4j.module.user.dto.UserInfoDTO;

/**
 * 登录响应 DTO（对齐 java-ai-native LoginResponseDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponseDTO {

    private String token;

    private String refreshToken;

    private UserInfoDTO user;
}
