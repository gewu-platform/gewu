package com.gewu.module.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户信息 DTO（对齐 java-ai-native UserInfoDTO）
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserInfoDTO {

    private Long id;

    private String username;

    private String email;

    private String nickname;

    private String avatar;

    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** P8b 权限管控：是否管理员（Agent 管理/审批可见性用） */
    private Boolean admin;
}
