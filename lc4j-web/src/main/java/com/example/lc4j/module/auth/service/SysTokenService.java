package com.example.lc4j.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.example.lc4j.module.auth.entity.SysToken;
import com.example.lc4j.module.auth.mapper.SysTokenMapper;
import com.example.lc4j.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

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

    /**
     * 登录成功后保存 token 记录
     */
    public SysToken saveToken(Long userId, String token, String refreshToken,
                              String deviceInfo, String ipAddress) {
        // 使该用户旧 token 失效
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
        log.info("Token 记录已保存, userId={}", userId);
        return sysToken;
    }

    /**
     * 验证 token 是否在数据库中存在且有效
     */
    public boolean isTokenValid(String token) {
        LambdaQueryWrapper<SysToken> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysToken::getToken, token)
                .eq(SysToken::getStatus, 1)
                .gt(SysToken::getExpiresAt, LocalDateTime.now());
        return sysTokenMapper.selectCount(wrapper) > 0;
    }

    /**
     * 使 token 失效（登出）
     */
    public void invalidateToken(String token) {
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
