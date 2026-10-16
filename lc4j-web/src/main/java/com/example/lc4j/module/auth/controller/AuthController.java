package com.example.lc4j.module.auth.controller;

import com.example.lc4j.common.ErrorCode;
import com.example.lc4j.common.BizException;
import com.example.lc4j.common.R;
import com.example.lc4j.module.auth.dto.*;
import com.example.lc4j.module.auth.entity.User;
import com.example.lc4j.module.auth.service.SysTokenService;
import com.example.lc4j.module.user.dto.UserInfoDTO;
import com.example.lc4j.module.user.service.UserService;
import com.example.lc4j.infrastructure.security.AuthThrottleService;
import com.example.lc4j.security.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

/**
 * 认证控制器（对齐 java-ai-native AuthController）
 * 提供登录、注册等认证相关接口
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserService userService;
    private final AuthenticationManager authenticationManager;
    private final JwtUtil jwtUtil;
    private final SysTokenService sysTokenService;
    private final AuthThrottleService authThrottleService;

    /**
     * 用户登录
     */
    @PostMapping("/login")
    public R<LoginResponseDTO> login(@Valid @RequestBody LoginRequestDTO loginRequest,
                                     HttpServletRequest httpRequest) {
        // 登录限流：按 用户名|IP 维度防暴力破解（对齐 native security.auth.throttle：5 次/300 秒）
        String throttleKey = loginRequest.getUsername() + "|" + getClientIp(httpRequest);
        if (!authThrottleService.isAllowed(throttleKey)) {
            log.warn("登录尝试过于频繁，已限流: key={}", throttleKey);
            throw new BizException(
                    ErrorCode.API_RATE_LIMIT.getCode(),
                    "登录尝试过于频繁，请稍后再试"
            );
        }

        // 验证用户凭据
        Authentication authentication;
        try {
            authentication = authenticationManager.authenticate(
                    new UsernamePasswordAuthenticationToken(
                            loginRequest.getUsername(),
                            loginRequest.getPassword()
                    )
            );
        } catch (BadCredentialsException e) {
            // 登录失败计入限流窗口
            authThrottleService.recordFailure(throttleKey);
            throw new BizException(
                    ErrorCode.UNAUTHORIZED.getCode(),
                    "用户名或密码错误"
            );
        }

        // 登录成功，重置限流计数
        authThrottleService.reset(throttleKey);

        SecurityContextHolder.getContext().setAuthentication(authentication);

        // 生成JWT令牌
        String token = jwtUtil.generateToken(loginRequest.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(loginRequest.getUsername());

        // 更新最后登录时间
        User user = userService.findByUsername(loginRequest.getUsername());
        if (user != null) {
            userService.updateLastLoginTime(user.getId());
            // 保存 token 到数据库（替代 Redis）
            String deviceInfo = httpRequest.getHeader("User-Agent");
            String ipAddress = getClientIp(httpRequest);
            sysTokenService.saveToken(user.getId(), token, refreshToken, deviceInfo, ipAddress);
        }

        log.info("用户登录成功: {}", loginRequest.getUsername());
        return R.success("登录成功", buildLoginResponse(token, refreshToken, user));
    }

    /**
     * 获取客户端真实 IP
     */
    private String getClientIp(HttpServletRequest request) {
        String ip = request.getHeader("X-Forwarded-For");
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getHeader("X-Real-IP");
        }
        if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
            ip = request.getRemoteAddr();
        }
        // 取第一个 IP（可能有代理）
        if (ip != null && ip.contains(",")) {
            ip = ip.split(",")[0].trim();
        }
        return ip;
    }

    /**
     * 用户注册
     */
    @PostMapping("/register")
    public R<LoginResponseDTO> register(@Valid @RequestBody RegisterRequestDTO registerRequest,
                                        HttpServletRequest httpRequest) {
        // 注册用户
        User user = userService.register(
                registerRequest.getUsername(),
                registerRequest.getEmail(),
                registerRequest.getPassword()
        );

        // 注册成功后自动登录
        String token = jwtUtil.generateToken(registerRequest.getUsername());
        String refreshToken = jwtUtil.generateRefreshToken(registerRequest.getUsername());

        // 保存 token 到数据库
        String deviceInfo = httpRequest.getHeader("User-Agent");
        String ipAddress = getClientIp(httpRequest);
        sysTokenService.saveToken(user.getId(), token, refreshToken, deviceInfo, ipAddress);

        log.info("用户注册成功: {}", registerRequest.getUsername());
        return R.success("注册成功", buildLoginResponse(token, refreshToken, user));
    }

    /**
     * 刷新令牌
     */
    @PostMapping("/refresh")
    public R<RefreshTokenResponseDTO> refreshToken(@Valid @RequestBody RefreshTokenRequestDTO refreshTokenRequest) {
        String refreshToken = refreshTokenRequest.getRefreshToken();
        String username = jwtUtil.getUsernameFromToken(refreshToken);
        User user = userService.findByUsername(username);

        if (user != null && jwtUtil.validateToken(refreshToken, username)) {
            // 使旧 refresh token 失效
            sysTokenService.invalidateRefreshToken(refreshToken);
            // 生成新 token 并保存
            String newToken = jwtUtil.generateToken(username);
            String newRefreshToken = jwtUtil.generateRefreshToken(username);
            sysTokenService.saveToken(user.getId(), newToken, newRefreshToken, null, null);

            return R.success(RefreshTokenResponseDTO.builder()
                    .token(newToken)
                    .refreshToken(newRefreshToken)
                    .build());
        }

        throw new BizException(
                ErrorCode.UNAUTHORIZED.getCode(),
                "刷新令牌失败：无效的刷新令牌"
        );
    }

    /**
     * 获取当前用户信息
     */
    @GetMapping("/me")
    public R<UserInfoDTO> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new BizException(
                    ErrorCode.UNAUTHORIZED.getCode(),
                    "未认证"
            );
        }

        String username = authentication.getName();
        User user = userService.findByUsername(username);

        if (user == null) {
            throw new BizException(
                    ErrorCode.NOT_FOUND.getCode(),
                    "用户不存在"
            );
        }

        return R.success(buildUserInfo(user));
    }

    /**
     * 用户登出
     * 使当前 token 失效（数据库标记 status=0）
     */
    @PostMapping("/logout")
    public R<Void> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            sysTokenService.invalidateToken(token);
        }
        return R.success("登出成功", null);
    }

    /**
     * 构建登录响应
     */
    private LoginResponseDTO buildLoginResponse(String token, String refreshToken, User user) {
        return LoginResponseDTO.builder()
                .token(token)
                .refreshToken(refreshToken)
                .user(buildUserInfo(user))
                .build();
    }

    /**
     * 构建用户信息 DTO
     */
    private UserInfoDTO buildUserInfo(User user) {
        if (user == null) {
            return null;
        }
        return UserInfoDTO.builder()
                .id(user.getId())
                .username(user.getUsername())
                .email(user.getEmail())
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .status(user.getStatus())
                .createTime(user.getCreateTime())
                .updateTime(user.getUpdateTime())
                .build();
    }
}
