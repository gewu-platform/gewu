package com.gewu.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.module.auth.entity.SysToken;
import com.gewu.module.auth.mapper.SysTokenMapper;
import com.gewu.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Token 服务（对齐 java-ai-native SysTokenService）
 * 使用数据库维护 token 状态（替代 Redis）
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SysTokenService {

    private final SysTokenMapper sysTokenMapper;
    private final JwtUtil jwtUtil;
    private final com.gewu.infrastructure.redis.RedisCacheService redisCacheService;

    /** U4：Redis token 状态键前缀（键用 sha256 摘要，避免明文 token） */
    private static final String TOKEN_KEY_PREFIX = "gewu:token:";

    /** U4：token 串 sha256 摘要 */
    private static String sha256(String token) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return token.replaceAll("[^a-zA-Z0-9]", "_");
        }
    }

    /**
     * 登录成功后保存 token 记录
     */
    public SysToken saveToken(Long userId, String token, String refreshToken,
                              String deviceInfo, String ipAddress) {
        // 使该用户旧 token 失效
        // U4：先取旧有效 token 串并删除其 Redis 键（单活会话语义）
        try {
            List<SysToken> actives = sysTokenMapper.selectList(
                    new LambdaQueryWrapper<SysToken>()
                            .eq(SysToken::getUserId, userId)
                            .eq(SysToken::getStatus, 1));
            for (SysToken old : actives) {
                redisCacheService.del(TOKEN_KEY_PREFIX + sha256(old.getToken()));
            }
        } catch (Exception e) {
            log.warn("[U4] 清理旧 token Redis 键失败（不影响登录）：userId={}, err={}", userId, e.getMessage());
        }
        sysTokenMapper.invalidateByUserId(userId);

        SysToken sysToken = new SysToken();
        sysToken.setUserId(userId);
        sysToken.setToken(token);
        sysToken.setRefreshToken(refreshToken);
        sysToken.setDeviceInfo(deviceInfo);
        sysToken.setIpAddress(ipAddress);
        sysToken.setStatus(1);
        sysToken.setExpiresAt(jwtUtil.getExpirationDate(token));
        sysToken.setRefreshExpiresAt(jwtUtil.getRefreshExpirationDate(token));
        sysToken.setCreateTime(LocalDateTime.now());
        sysToken.setUpdateTime(LocalDateTime.now());

        sysTokenMapper.insert(sysToken);
        // U4：写 Redis 有效标记，TTL=token 剩余有效期
        try {
            Duration ttl = Duration.between(LocalDateTime.now(), sysToken.getExpiresAt());
            if (!ttl.isNegative() && !ttl.isZero()) {
                redisCacheService.set(TOKEN_KEY_PREFIX + sha256(token), "1", ttl);
            }
        } catch (Exception e) {
            log.warn("[U4] 写 token Redis 标记失败（不影响登录）：err={}", e.getMessage());
        }
        log.info("Token 记录已保存, userId={}", userId);
        return sysToken;
    }

    /**
     * 验证 token 是否在数据库中存在且有效
     */
    public boolean isTokenValid(String token) {
        // U4：Redis 快路径（命中免 DB）；未命中回源 DB 并回填
        String redisKey = TOKEN_KEY_PREFIX + sha256(token);
        String cached = redisCacheService.get(redisKey);
        if ("1".equals(cached)) {
            return true;
        }
        if ("0".equals(cached)) {
            return false;
        }
        LambdaQueryWrapper<SysToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysToken::getToken, token)
                .eq(SysToken::getStatus, 1)
                .gt(SysToken::getExpiresAt, LocalDateTime.now());
        SysToken row = sysTokenMapper.selectOne(wrapper);
        boolean valid = row != null;
        try {
            if (valid) {
                Duration ttl = Duration.between(LocalDateTime.now(), row.getExpiresAt());
                if (!ttl.isNegative() && !ttl.isZero()) {
                    redisCacheService.set(redisKey, "1", ttl);
                }
            } else {
                redisCacheService.set(redisKey, "0", Duration.ofMinutes(10));
            }
        } catch (Exception ignored) {
        }
        return valid;
    }

    /**
     * 使 token 失效（登出）
     */
    public void invalidateToken(String token) {
        // U4：先删 Redis 有效标记
        redisCacheService.del(TOKEN_KEY_PREFIX + sha256(token));
        LambdaQueryWrapper<SysToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysToken::getToken, token)
                .eq(SysToken::getStatus, 1);
        SysToken sysToken = sysTokenMapper.selectOne(wrapper);
        if (sysToken != null) {
            sysToken.setStatus(0);
            sysToken.setUpdateTime(LocalDateTime.now());
            sysTokenMapper.updateById(sysToken);
            log.info("Token 已失效, userId={}", sysToken.getUserId());
        }
    }

    /**
     * 使指定 refresh token 失效（刷新 token 时）
     */
    public void invalidateRefreshToken(String refreshToken) {
        // U4：删除关联 access token 的 Redis 标记
        try {
            SysToken row = sysTokenMapper.selectOne(new LambdaQueryWrapper<SysToken>()
                    .eq(SysToken::getRefreshToken, refreshToken)
                    .eq(SysToken::getStatus, 1));
            if (row != null && row.getToken() != null) {
                redisCacheService.del(TOKEN_KEY_PREFIX + sha256(row.getToken()));
            }
        } catch (Exception e) {
            log.warn("[U4] 删 refresh 关联 token Redis 键失败：err={}", e.getMessage());
        }
        sysTokenMapper.invalidateByRefreshToken(refreshToken);
    }

    /**
     * 清理过期 token（可定时调用）
     */
    public int cleanExpiredTokens() {
        LambdaQueryWrapper<SysToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysToken::getStatus, 1)
                .lt(SysToken::getExpiresAt, LocalDateTime.now());
        SysToken expired = new SysToken();
        expired.setStatus(0);
        expired.setUpdateTime(LocalDateTime.now());
        return sysTokenMapper.update(expired, wrapper);
    }
}
