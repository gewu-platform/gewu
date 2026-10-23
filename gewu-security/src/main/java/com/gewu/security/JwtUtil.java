package com.gewu.security;

import cn.hutool.jwt.JWT;
import cn.hutool.jwt.JWTUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

/**
 * JWT 工具类（对齐 java-ai-native JwtUtil）
 * 基于 hutool-jwt 实现
 *
 * @author Matilda
 * @since 2026-10-16
 */
@Component
public class JwtUtil {

    @Value("${jwt.secret:mySecretKeyMustBeAtLeast32CharactersLongForHS512Algorithm}")
    private String secret;

    @Value("${jwt.expiration:86400}")
    private Long expiration; // 默认24小时（秒）

    private byte[] getKeyBytes() {
        return secret.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * 从令牌中获取用户名
     */
    public String getUsernameFromToken(String token) {
        JWT jwt = JWTUtil.parseToken(token).setKey(getKeyBytes());
        return jwt.getPayload().getClaimsJson().get("sub").toString();
    }

    /**
     * 从令牌中获取过期时间
     */
    public Date getExpirationDateFromToken(String token) {
        JWT jwt = JWTUtil.parseToken(token).setKey(getKeyBytes());
        Object exp = jwt.getPayload().getClaimsJson().get("exp");
        if (exp instanceof Long) {
            return new Date((Long) exp * 1000);
        } else if (exp instanceof Integer) {
            return new Date(((Integer) exp).longValue() * 1000);
        } else if (exp instanceof Number) {
            return new Date(((Number) exp).longValue() * 1000);
        }
        throw new RuntimeException("JWT exp claim 格式异常");
    }

    /**
     * 检查令牌是否过期
     */
    private Boolean isTokenExpired(String token) {
        final Date expirationDate = getExpirationDateFromToken(token);
        return expirationDate.before(new Date());
    }

    /**
     * 获取访问令牌的过期时间（供 token 服务使用）
     */
    public LocalDateTime getExpirationDate(String token) {
        return getExpirationDateFromToken(token).toInstant()
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * 获取刷新令牌的过期时间（供 token 服务使用）
     */
    public LocalDateTime getRefreshExpirationDate(String token) {
        JWT jwt = JWTUtil.parseToken(token).setKey(getKeyBytes());
        Object exp = jwt.getPayload().getClaimsJson().get("exp");
        long expSeconds;
        if (exp instanceof Long) {
            expSeconds = (Long) exp;
        } else if (exp instanceof Integer) {
            expSeconds = ((Integer) exp).longValue();
        } else if (exp instanceof Number) {
            expSeconds = ((Number) exp).longValue();
        } else {
            throw new RuntimeException("JWT exp claim 格式异常");
        }
        return java.time.Instant.ofEpochSecond(expSeconds)
                .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime();
    }

    /**
     * 生成访问令牌
     */
    public String generateToken(String username) {
        return doGenerateToken(username, expiration);
    }

    /**
     * 生成刷新令牌（有效期 7 天）
     */
    public String generateRefreshToken(String username) {
        return doGenerateToken(username, expiration * 7);
    }

    /**
     * 生成令牌的具体实现
     */
    private String doGenerateToken(String username, Long expireTime) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("sub", username);
        payload.put("created", System.currentTimeMillis() / 1000);
        payload.put("exp", System.currentTimeMillis() / 1000 + expireTime);
        return JWTUtil.createToken(payload, getKeyBytes());
    }

    /**
     * 验证令牌（签名 + 用户名 + 过期时间）
     */
    public Boolean validateToken(String token, String username) {
        try {
            JWT jwt = JWTUtil.parseToken(token).setKey(getKeyBytes());
            if (!jwt.verify()) {
                return false;
            }
            String tokenUsername = jwt.getPayload().getClaimsJson().get("sub").toString();
            return tokenUsername.equals(username) && !isTokenExpired(token);
        } catch (Exception e) {
            return false;
        }
    }
}
