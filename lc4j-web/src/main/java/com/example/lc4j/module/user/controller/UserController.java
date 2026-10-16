package com.example.lc4j.module.user.controller;

import com.example.lc4j.common.ErrorCode;
import com.example.lc4j.common.BizException;
import com.example.lc4j.common.R;
import com.example.lc4j.module.auth.entity.User;
import com.example.lc4j.module.user.dto.UserInfoDTO;
import com.example.lc4j.module.user.service.UserService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;

/**
 * 用户控制器（对齐 java-ai-native UserController）
 * 提供用户相关的接口
 */
@Slf4j
@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final PasswordEncoder passwordEncoder;

    /**
     * 获取当前用户信息
     */
    @GetMapping("/profile")
    public R<UserInfoDTO> getCurrentUser(Authentication authentication) {
        String username = resolveUsername(authentication);
        User user = userService.findByUsername(username);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }
        return R.success(buildUserInfo(user));
    }

    /**
     * 更新用户信息
     */
    @PutMapping("/profile")
    public R<UserInfoDTO> updateUserProfile(
            @RequestBody UserUpdateRequest request,
            Authentication authentication) {

        String username = resolveUsername(authentication);
        User existingUser = userService.findByUsername(username);
        if (existingUser == null) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }

        if (request.getNickname() != null) {
            existingUser.setNickname(request.getNickname());
        }
        if (request.getAvatar() != null) {
            existingUser.setAvatar(request.getAvatar());
        }
        existingUser.setUpdateTime(LocalDateTime.now());
        userService.updateById(existingUser);

        log.info("用户信息更新成功: {}", username);
        return R.success("用户信息更新成功", buildUserInfo(existingUser));
    }

    /**
     * 更改密码
     */
    @PutMapping("/password")
    public R<Void> changePassword(
            @RequestBody ChangePasswordRequest request,
            Authentication authentication) {

        String username = resolveUsername(authentication);
        User user = userService.findByUsername(username);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(), "用户不存在");
        }

        // 验证原密码
        if (request.getOldPassword() == null || !passwordEncoder.matches(request.getOldPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.UNAUTHORIZED.getCode(), "原密码不正确");
        }

        user.setPassword(passwordEncoder.encode(request.getNewPassword()));
        user.setUpdateTime(LocalDateTime.now());
        userService.updateById(user);

        log.info("用户密码更改成功: {}", username);
        return R.success("密码更改成功", null);
    }

    /**
     * 解析认证用户名
     */
    private String resolveUsername(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || "anonymousUser".equals(authentication.getName())) {
            throw new BizException(ErrorCode.UNAUTHORIZED.getCode(), "未认证");
        }
        return authentication.getName();
    }

    /**
     * 构建用户信息 DTO
     */
    private UserInfoDTO buildUserInfo(User user) {
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

    /**
     * 用户信息更新请求
     */
    @Data
    public static class UserUpdateRequest {
        private String nickname;
        private String avatar;
    }

    /**
     * 密码更改请求
     */
    @Data
    public static class ChangePasswordRequest {
        private String oldPassword;
        private String newPassword;
    }
}
