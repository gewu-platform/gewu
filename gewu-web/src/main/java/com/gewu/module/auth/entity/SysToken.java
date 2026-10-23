package com.gewu.module.auth.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * Token 实体（对齐 java-ai-native SysToken）
 * 数据库维护 token 状态（替代 Redis）
 */
@Data
@TableName("sys_token")
public class SysToken implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    private Long userId;

    /**
     * 访问令牌
     */
    private String token;

    /**
     * 刷新令牌
     */
    private String refreshToken;

    /**
     * 设备信息
     */
    private String deviceInfo;

    /**
     * IP 地址
     */
    private String ipAddress;

    /**
     * 状态：0-失效，1-有效
     */
    private Integer status;

    /**
     * 访问令牌过期时间
     */
    private LocalDateTime expiresAt;

    /**
     * 刷新令牌过期时间
     */
    private LocalDateTime refreshExpiresAt;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

    /**
     * 更新时间
     */
    private LocalDateTime updateTime;
}
