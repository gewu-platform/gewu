package com.gewu.module.audit.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.R;
import com.gewu.module.audit.entity.AuditLog;
import com.gewu.module.audit.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * V2.4.0 C2/T2.3：审计日志查询（ADMIN）
 *
 * <p>端点：GET /api/settings/audit-logs——支持按 actor / action / targetType /
 * 时间区间筛选；默认返回最近 100 条（防止全表拉取）。</p>
 *
 * <p>认证：挂在 /api/settings/** 下，SecurityConfig hasRole("ADMIN") 覆盖。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/audit-logs")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    /** 单次返回上限（防全表拉取） */
    private static final int MAX_LIMIT = 200;

    @GetMapping
    public R<List<AuditLog>> list(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.min(Math.max(limit, 1), MAX_LIMIT);

        LambdaQueryWrapper<AuditLog> qw = new LambdaQueryWrapper<>();
        if (actor != null && !actor.isBlank()) {
            qw.eq(AuditLog::getActor, actor.trim());
        }
        if (action != null && !action.isBlank()) {
            qw.eq(AuditLog::getAction, action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            qw.eq(AuditLog::getTargetType, targetType.trim());
        }
        if (from != null) {
            qw.ge(AuditLog::getCreateTime, from);
        }
        if (to != null) {
            qw.le(AuditLog::getCreateTime, to);
        }
        qw.orderByDesc(AuditLog::getId).last("LIMIT " + safeLimit);
        return R.success(auditLogMapper.selectList(qw));
    }
}
