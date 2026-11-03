package com.gewu.module.chat.controller;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.common.R;
import com.gewu.module.chat.dto.*;
import com.gewu.module.chat.service.ContextCompactService;
import com.gewu.module.chat.service.ConversationService;
import com.gewu.module.monitor.service.UsageQuotaService;
import com.gewu.module.user.service.UserSettingService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天设置与模式管理 REST API（/api/chat）。
 *
 * <p>承载上下文压缩（手动压缩 / 读写压缩配置 / 上下文用量）、模式切换、
 * 模型清单与切换共 9 个端点。</p>
 *
 * <p>从原 ChatController（586 行 / 27 端点）按资源边界拆出。共享 /api/chat 前缀，
 * (verb, path) 与原实现逐一对应，前端无感知。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatSettingsController {

    private final ConversationService conversationService;
    private final ContextCompactService contextCompactService;
    private final UserSettingService userSettingService;
    private final LangChain4jConfig langChain4jConfig;
    private final UsageQuotaService usageQuotaService;

    /**
     * T3.1 手动压缩：旧消息合并进滚动摘要（当前激活 chat 模型执行）
     */
    @PostMapping("/session/{sessionId}/compact")
    public R<java.util.Map<String, Object>> compactSession(
            @PathVariable String sessionId,
            @RequestParam(required = false) Integer keepRecent) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        conversationService.ensureAccessible(sessionId, currentUserId, usageQuotaService.isCurrentUserAdmin());
        java.util.Map<String, Object> conf = contextCompactService.loadUserSettings(currentUserId);
        int keep = keepRecent != null ? keepRecent : ((Number) conf.get("keepRecent")).intValue();
        String summary = contextCompactService.compact(sessionId, currentUserId, keep);
        java.util.Map<String, Object> r = new java.util.HashMap<>();
        r.put("summary", summary == null ? "" : summary);
        r.put("keepRecent", keep);
        return R.success(r);
    }

    /**
     * T3.2 读当前用户压缩配置
     */
    @GetMapping("/settings/compact")
    public R<java.util.Map<String, Object>> getCompactSettings() {
        return R.success(userSettingService.loadSettings(usageQuotaService.resolveCurrentUserId()));
    }

    /**
     * T3.3 写当前用户压缩配置（部分更新：只传的字段生效）
     */
    @PostMapping("/settings/compact")
    public R<java.util.Map<String, Object>> saveCompactSettings(
            @RequestBody java.util.Map<String, Object> patch) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        userSettingService.saveSettings(currentUserId, patch);
        return R.success(userSettingService.loadSettings(currentUserId));
    }

    /**
     * T5 上下文用量（前端环形指示）：used/threshold/percent/autoCompact
     */
    @GetMapping("/session/{sessionId}/context-usage")
    public R<java.util.Map<String, Object>> getContextUsage(@PathVariable String sessionId) {
        Long currentUserId = usageQuotaService.resolveCurrentUserId();
        conversationService.ensureAccessible(sessionId, currentUserId, usageQuotaService.isCurrentUserAdmin());
        return R.success(contextCompactService.getContextUsage(sessionId, currentUserId));
    }

    /**
     * 切换模式（文本/多模态，多模态预留）
     */
    @PostMapping("/mode")
    public R<ModeStatusView> switchMode(@RequestBody SwitchModeRequest request) {
        boolean multimodal = request.getMultimodal() != null && request.getMultimodal();
        log.info("切换模式请求：multimodal={}", multimodal);

        if (multimodal) {
            log.info("多模态模式预留中（P3 后置图像输入），保持文本模式");
        }

        String currentModel = langChain4jConfig.getActiveModel();
        return R.success(ModeStatusView.of("text", false, currentModel));
    }

    /**
     * 获取当前模式状态
     */
    @GetMapping("/mode")
    public R<ModeStatusView> getMode() {
        String currentModel = langChain4jConfig.getActiveModel();
        return R.success(ModeStatusView.of("text", false, currentModel));
    }

    /**
     * 切换 AI 模型
     */
    @PostMapping("/model")
    public R<Map<String, Object>> switchModel(@RequestBody Map<String, String> request) {
        String modelId = request.get("modelId");
        log.info("切换 AI 模型：{}", modelId);

        LangChain4jConfig.ModelConfig config = langChain4jConfig.getModels().get(modelId);
        if (config == null) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(),
                    "未知模型 ID: " + modelId);
        }

        String oldModel = langChain4jConfig.getActiveModel();
        langChain4jConfig.setActiveModel(modelId);
        langChain4jConfig.evictAllChatModels();

        Map<String, Object> response = new HashMap<>();
        response.put("oldModel", oldModel);
        response.put("newModel", modelId);
        response.put("modelName", config.getModelName());
        return R.success("模型切换成功", response);
    }

    /**
     * 获取可用模型列表
     */
    @GetMapping("/models")
    public R<Map<String, Object>> getAvailableModels() {
        List<Map<String, Object>> models = langChain4jConfig.getModels().entrySet().stream()
                .map(entry -> {
                    String modelId = entry.getKey();
                    LangChain4jConfig.ModelConfig config = entry.getValue();
                    Map<String, Object> item = new HashMap<>();
                    item.put("id", modelId);
                    item.put("provider", config.getProvider());
                    item.put("modelName", config.getModelName());
                    item.put("description", config.getDescription() != null
                            ? config.getDescription() : config.getModelName());
                    item.put("active", modelId.equals(langChain4jConfig.getActiveModel()));
                    return item;
                })
                .collect(Collectors.toList());

        Map<String, Object> response = new HashMap<>();
        response.put("models", models);
        response.put("activeModel", langChain4jConfig.getActiveModel());
        response.put("count", models.size());
        return R.success(response);
    }

    /**
     * 获取当前模型信息
     */
    @GetMapping("/model")
    public R<Map<String, Object>> getCurrentModel() {
        String modelId = langChain4jConfig.getActiveModel();
        LangChain4jConfig.ModelConfig config = langChain4jConfig.getModels().get(modelId);

        Map<String, Object> modelInfo = new HashMap<>();
        modelInfo.put("id", modelId);
        modelInfo.put("modelName", config != null ? config.getModelName() : "unknown");
        modelInfo.put("provider", config != null ? config.getProvider() : "unknown");
        modelInfo.put("description", config != null && config.getDescription() != null
                ? config.getDescription() : modelId);
        modelInfo.put("active", true);
        return R.success(modelInfo);
    }

}
