package com.gewu.module.chat.controller;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.common.R;
import com.gewu.module.audit.service.AuditService;
import com.gewu.module.chat.dto.*;
import com.gewu.module.chat.entity.ChatSession;
import com.gewu.module.chat.service.ConversationService;
import com.gewu.module.chat.service.TokenUsageService;
import com.gewu.module.monitor.service.UsageQuotaService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 会话生命周期与组织 REST API（/api/chat）。
 *
 * <p>承载会话创建/复用/清空/删除/重命名/派生、会话列表与详情、消息列表、
 * Token 统计、会话分组（归组 / 解散 / 清单）共 13 个端点。</p>
 *
 * <p>从原 ChatController（586 行 / 27 端点）按资源边界拆出。共享 /api/chat 前缀，
 * (verb, path) 与原实现逐一对应，前端无感知。</p>
 *
 * <p>访问控制：所有涉及具体 sessionId 的端点先经 ensureAccessible 校验归属
 * （本人或 ADMIN），管理面操作（清空/删除/解散分组）额外落审计。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatSessionController {

    private final ConversationService conversationService;
    private final TokenUsageService tokenUsageService;
    /** V2.4.0 C2：审计日志（管理面操作留痕；落库失败不影响业务） */
    private final AuditService auditService;
    private final LangChain4jConfig langChain4jConfig;
    private final UsageQuotaService usageQuotaService;

    /**
     * 创建新会话
     */
    @PostMapping("/session")
    public R<SessionResponse> createSession() {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        String sessionId = conversationService.createSession(currentUserId);
        log.info("会话创建成功：sessionId={}", sessionId);
        return R.success("会话创建成功", SessionResponse.of(sessionId, null));
    }

    /**
     * 获取或创建会话（用于会话复用）
     */
    @PostMapping("/session/active")
    public R<SessionResponse> getOrCreateSession() {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        String sessionId = conversationService.getOrCreateSession(currentUserId);
        ChatSession session = conversationService.getSession(sessionId);
        SessionResponse response = SessionResponse.of(
                sessionId, session != null ? session.getMode() : "text",
                session != null ? session.getModel() : langChain4jConfig.getActiveModel(),
                session != null
        );
        log.info("会话获取/创建：sessionId={}, reused={}", sessionId, response.getReused());
        return R.success(response);
    }

    /**
     * 清除会话历史
     */
    @PostMapping("/session/{sessionId}/clear")
    public R<SessionResponse> clearSession(@PathVariable String sessionId, jakarta.servlet.http.HttpServletRequest httpRequest) {
        conversationService.ensureAccessible(sessionId,
                usageQuotaService.resolveCurrentUserId(), usageQuotaService.isCurrentUserAdmin());
        conversationService.clearSession(sessionId);
        log.info("会话历史已清除：sessionId={}", sessionId);
        // V2.4.0 C2：审计——清空会话消息
        auditService.record(httpRequest, "session.clear", "chat_session", sessionId, null, true);
        return R.success("会话历史已清除", SessionResponse.of(sessionId, null));
    }

    /**
     * 删除会话
     */
    @DeleteMapping("/session/{sessionId}")
    public R<SessionResponse> deleteSession(@PathVariable String sessionId, jakarta.servlet.http.HttpServletRequest httpRequest) {
        conversationService.ensureAccessible(sessionId,
                usageQuotaService.resolveCurrentUserId(), usageQuotaService.isCurrentUserAdmin());
        conversationService.deleteSession(sessionId);
        log.info("会话已删除：sessionId={}", sessionId);
        // V2.4.0 C2：审计——删除会话
        auditService.record(httpRequest, "session.delete", "chat_session", sessionId, null, true);
        return R.success("会话已删除", SessionResponse.of(sessionId, null));
    }

    /**
     * 获取会话列表
     */
    @GetMapping("/sessions")
    public R<SessionListResponse> getSessions(
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        List<ChatSession> sessions = conversationService.getSessionsByUserId(currentUserId);
        long total = conversationService.getSessionCountByUserId(currentUserId);
        return R.success(SessionListResponse.of(sessions, (int) total, page, size));
    }

    /**
     * T2.1 会话重命名（remark 即标题）
     */
    @PatchMapping("/session/{sessionId}/title")
    public R<SessionResponse> renameSession(@PathVariable String sessionId,
                                               @RequestBody Map<String, String> body) {
        conversationService.ensureAccessible(sessionId,
                usageQuotaService.resolveCurrentUserId(), usageQuotaService.isCurrentUserAdmin());
        String remark = body.get("remark");
        if (remark == null || remark.isBlank()) {
            throw new BizException(400, "会话名称不能为空");
        }
        if (remark.length() > 100) {
            throw new BizException(400, "会话名称过长（≤100 字符）");
        }
        conversationService.renameSession(sessionId, remark.trim());
        return R.success("会话已重命名", SessionResponse.of(sessionId, null));
    }

    /**
     * V1.1.0 C1/T1.2：派生子会话——克隆父 systemPrompt + 上下文摘要为起点，父子独立演进
     */
    @PostMapping("/session/{sessionId}/derive")
    public R<SessionResponse> deriveSession(@PathVariable String sessionId) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        conversationService.ensureAccessible(sessionId, currentUserId, usageQuotaService.isCurrentUserAdmin());
        String childId = conversationService.deriveSession(sessionId, currentUserId);
        log.info("派生子会话成功：parent={}, child={}", sessionId, childId);
        return R.success("子会话已创建", SessionResponse.of(childId, null));
    }

    /**
     * 获取会话详情
     */
    @GetMapping("/session/{sessionId}")
    public R<ChatSession> getSession(@PathVariable String sessionId) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        ChatSession session = conversationService.ensureAccessible(
                sessionId, currentUserId, usageQuotaService.isCurrentUserAdmin());
        return R.success(session);
    }

    /**
     * 获取会话消息列表
     */
    @GetMapping("/session/{sessionId}/messages")
    public R<MessageListResponse> getMessages(@PathVariable String sessionId) {
        conversationService.ensureAccessible(sessionId,
                usageQuotaService.resolveCurrentUserId(), usageQuotaService.isCurrentUserAdmin());
        List<com.gewu.module.chat.entity.ChatMessage> messages =
                conversationService.getSessionMessages(sessionId);
        return R.success(MessageListResponse.of(messages));
    }

    /**
     * 获取 Token 使用统计
     */
    @GetMapping("/session/{sessionId}/stats")
    public R<TokenStatsResponse> getTokenStats(@PathVariable String sessionId) {
        com.gewu.module.chat.entity.TokenUsage stats = tokenUsageService.getSessionStats(sessionId);
        TokenStatsResponse response;
        if (stats != null) {
            response = TokenStatsResponse.of(sessionId,
                    stats.getInputTokens() != null ? stats.getInputTokens() : 0,
                    stats.getOutputTokens() != null ? stats.getOutputTokens() : 0,
                    stats.getTotalTokens() != null ? stats.getTotalTokens() : 0,
                    stats.getCost() != null ? stats.getCost() : BigDecimal.ZERO);
        } else {
            response = TokenStatsResponse.of(sessionId, 0, 0, 0, BigDecimal.ZERO);
        }
        return R.success(response);
    }

    /**
     * T2.2 会话归组 / 移组（groupName=null 或空 = 移出分组）
     */
    @PatchMapping("/session/{sessionId}/group")
    public R<SessionResponse> setSessionGroup(@PathVariable String sessionId,
                                                 @RequestBody Map<String, String> body) {
        conversationService.ensureAccessible(sessionId,
                usageQuotaService.resolveCurrentUserId(), usageQuotaService.isCurrentUserAdmin());
        String groupName = body.get("groupName");
        if (groupName != null && groupName.length() > 50) {
            throw new BizException(400, "分组名过长（≤50 字符）");
        }
        conversationService.setSessionGroup(sessionId,
                groupName == null || groupName.isBlank() ? null : groupName.trim());
        return R.success("会话分组已更新", SessionResponse.of(sessionId, null));
    }

    /**
     * T2.3 删除分组（会话回落「默认」组）
     */
    @DeleteMapping("/groups/{groupName}")
    public R<Map<String, Object>> dissolveGroup(@PathVariable String groupName, jakarta.servlet.http.HttpServletRequest httpRequest) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        if (currentUserId == null) {
            throw new BizException(401, "请先登录");
        }
        int affected = conversationService.dissolveGroup(groupName, currentUserId);
        // V2.4.0 C2：审计——解散会话分组
        auditService.record(httpRequest, "group.dissolve", "chat_session_group", groupName, null, true);
        return R.success("分组已删除", Map.of("affected", affected));
    }

    /**
     * T2.2 当前用户分组清单
     */
    @GetMapping("/groups")
    public R<List<String>> listGroups() {
        return R.success(conversationService.listGroups(usageQuotaService.resolveCurrentUserId()));
    }

}
