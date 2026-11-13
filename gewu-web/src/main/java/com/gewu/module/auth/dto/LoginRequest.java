package com.gewu.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 用户登录请求 DTO
 */
@Data
public class LoginRequest {
    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "密码不能为空")
    private String password;

    /**
     * P19：设备标识（前端 localStorage UUID；缺省时后端回退 User-Agent 派生，兼容旧客户端）
     */
    @Size(max = 64, message = "设备标识过长（≤64 字符）")
    private String deviceId;
}
