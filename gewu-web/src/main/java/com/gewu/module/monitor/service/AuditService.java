package com.gewu.module.monitor.service;

import com.gewu.module.monitor.entity.AuditLog;
import com.gewu.module.monitor.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * V2.4.0 C2/T2.1+T2.2：审计日志服务（管理面操作留痕）
 *
 * <p>覆盖：Agent 定义增删改、模型与密钥变更、插件安装卸载、风险级调整、会话清理。</p>
 *
 * <p>设计约束：</p>
 * <ul>
 *   <li><b>不写明文密钥</b>：detail 只允许传结构化摘要，本服务对疑似密钥键做掩码（C2.3）。</li>
 *   <li><b>失败不影响主流程</b>：落库异常只 WARN，不向上抛——审计是旁路，不能阻断业务。</li>
 *   <li><b>IP 取 X-Forwarded-For 首地址</b>，回落到 remoteAddr（与限流一致）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;

    /** 疑似密钥的键名片段（出现即掩码，防误落明文） */
    private static final String[] SECRET_HINTS = {
            "key", "secret", "token", "password", "passwd", "credential", "apikey", "api_key"
    };

    /**
     * 记录一条审计。
     *
     * @param request   可为 null（非 HTTP 上下文，如定时任务）
     * @param action    动作，如 agent.create
     * @param targetType 对象类型
     * @param targetId  对象标识
     * @param detail    变更摘要（Map 会做密钥掩码）
     * @param success   是否成功
     */
    public void record(HttpServletRequest request, String action, String targetType,
                       String targetId, Object detail, boolean success) {
        try {
            AuditLog row = new AuditLog();
            row.setActor(resolveActor());
            row.setAction(action == null ? "unknown" : action);
            row.setTargetType(targetType);
            row.setTargetId(targetId == null ? null : String.valueOf(targetId));
            row.setDetail(maskDetail(detail));
            row.setIp(request == null ? null : resolveIp(request));
            row.setSuccess(success ? 1 : 0);
            row.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(row);
            log.info("[Audit] {} actor={} target={}:{} success={}",
                    row.getAction(), row.getActor(), targetType, row.getTargetId(), success);
        } catch (Exception e) {
            // 旁路失败只告警，绝不阻断业务
            log.warn("[Audit] 审计落库失败（不影响业务）：action={}, err={}", action, e.getMessage());
        }
    }

    /** 便捷重载：无请求对象 */
    public void record(String action, String targetType, String targetId, Object detail, boolean success) {
        record(null, action, targetType, targetId, detail, success);
    }

    // ==================== 内部方法 ====================

    private String resolveActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated()) {
            Object p = auth.getPrincipal();
            if (p instanceof UserDetails ud && ud.getUsername() != null) {
                return ud.getUsername();
            }
            if (p instanceof String s && !"anonymousUser".equals(s)) {
                return s;
            }
        }
        return "anonymous";
    }

    private String resolveIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            return xff.split(",")[0].trim();
        }
        String ip = request.getRemoteAddr();
        return ip == null ? null : ip;
    }

    /**
     * detail 脱敏：Map 形式时，键名含密钥提示词的值一律置为 ***；
     * 其他类型取字符串表示（超长截断 2000）。
     */
    private String maskDetail(Object detail) {
        if (detail == null) {
            return null;
        }
        String s;
        if (detail instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                String k = String.valueOf(e.getKey());
                sb.append(k).append("=");
                sb.append(isSecretKey(k) ? "***" : safe(String.valueOf(e.getValue())));
            }
            sb.append("}");
            s = sb.toString();
        } else {
            s = safe(String.valueOf(detail));
        }
        return s.length() > 2000 ? s.substring(0, 2000) + "...(truncated)" : s;
    }

    private boolean isSecretKey(String key) {
        String k = key.toLowerCase();
        for (String hint : SECRET_HINTS) {
            if (k.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    private String safe(String v) {
        return v == null ? "null" : v.replace("\n", " ").replace("\r", " ");
    }
}
