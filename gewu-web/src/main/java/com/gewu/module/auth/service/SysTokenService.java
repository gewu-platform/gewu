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
 * Token 服务
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
    /**
     * P19：按 refresh token 反查设备标识（刷新流程保持原设备归属）
     */
    public String findDeviceIdByRefreshToken(String refreshToken) {
        try {
            return sysTokenMapper.selectDeviceIdByRefreshToken(refreshToken);
        } catch (Exception e) {
            log.warn("[P19] 反查设备标识失败（忽略）：{}", e.getMessage());
            return null;
        }
    }

    /** P19：单用户活跃设备数上限（超出淘汰最久未活跃设备） */
    private static final int MAX_DEVICES = 5;

    public SysToken saveToken(Long userId, String token, String refreshToken,
                              String deviceInfo, String ipAddress, String deviceId) {
        // P19 多设备共活：仅失效「同用户 + 同设备」的旧 token，不同设备各自保留
        // U4：先取同设备旧有效 token 串并删除其 Redis 键
        try {
            List<SysToken> actives = sysTokenMapper.selectList(
                    new LambdaQueryWrapper<SysToken>()
                            .eq(SysToken::getUserId, userId)
                            .eq(SysToken::getDeviceId, deviceId)
                            .eq(SysToken::getStatus, 1));
            for (SysToken old : actives) {
                redisCacheService.del(TOKEN_KEY_PREFIX + sha256(old.getToken()));
            }
        } catch (Exception e) {
            log.warn("[P19] 清理同设备旧 token Redis 键失败（不影响登录）：userId={}, err={}", userId, e.getMessage());
        }
        sysTokenMapper.invalidateByUserAndDevice(userId, deviceId);

        // P19：设备数上限——活跃设备达到上限时淘汰最久未活跃者（含其 Redis 键）
        try {
            List<String> activeDevices = sysTokenMapper.selectActiveDeviceIdsByLeastRecent(userId);
            while (activeDevices != null && activeDevices.size() >= MAX_DEVICES) {
                String oldest = activeDevices.remove(0);
                List<SysToken> evicting = sysTokenMapper.selectList(
                        new LambdaQueryWrapper<SysToken>()
                                .eq(SysToken::getUserId, userId)
                                .eq(SysToken::getDeviceId, oldest)
                                .eq(SysToken::getStatus, 1));
                for (SysToken old : evicting) {
                    redisCacheService.del(TOKEN_KEY_PREFIX + sha256(old.getToken()));
                }
                sysTokenMapper.invalidateByUserAndDevice(userId, oldest);
                log.info("[P19] 设备数超上限，淘汰最久未活跃设备：userId={}, deviceId={}", userId, oldest);
            }
        } catch (Exception e) {
            log.warn("[P19] 设备上限淘汰失败（不影响登录）：userId={}, err={}", userId, e.getMessage());
        }

        SysToken sysToken = new SysToken();
        sysToken.setUserId(userId);
        sysToken.setToken(token);
        sysToken.setRefreshToken(refreshToken);
        sysToken.setDeviceInfo(deviceInfo);
        sysToken.setDeviceId(deviceId);
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
