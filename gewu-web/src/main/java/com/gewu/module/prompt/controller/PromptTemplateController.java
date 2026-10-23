package com.gewu.module.prompt.controller;

import com.gewu.common.R;
import com.gewu.module.prompt.dto.PromptExecuteRequest;
import com.gewu.module.prompt.dto.PromptExecuteResponse;
import com.gewu.module.prompt.dto.PromptRollbackRequest;
import com.gewu.module.prompt.entity.PromptTemplate;
import com.gewu.module.prompt.service.PromptTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Prompt 模板 Controller（对齐 java-ai-native PromptTemplateController）
 *
 * 端点：/api/prompt/execute、/list、/{name}、/create、/rollback
 * 认证：SecurityConfig anyRequest().authenticated() 已覆盖，无需单独放行。
 */
@Slf4j
@RestController
@RequestMapping("/api/prompt")
@RequiredArgsConstructor
public class PromptTemplateController {

    private final PromptTemplateService promptTemplateService;

    /**
     * 执行 Prompt 模板
     */
    @PostMapping("/execute")
    public R<PromptExecuteResponse> executeTemplate(@RequestBody @Valid PromptExecuteRequest request) {
        log.info("执行 Prompt 模板请求：templateName={}, variables={}",
                request.getTemplateName(),
                request.getVariables() != null ? request.getVariables().keySet() : "无");
        PromptExecuteResponse response = promptTemplateService.executeTemplate(
                request.getTemplateName(), request.getVariables());
        return R.success(response);
    }

    /**
     * 列出所有模板
     */
    @GetMapping("/list")
    public R<List<PromptTemplate>> listTemplates() {
        return R.success(promptTemplateService.listTemplates());
    }

    /**
     * 获取模板详情（最新版本）
     */
    @GetMapping("/{name}")
    public R<PromptTemplate> getTemplate(@PathVariable String name) {
        return R.success(promptTemplateService.getTemplate(name));
    }

    /**
     * 创建模板（版本自动 +1）
     */
    @PostMapping("/create")
    public R<PromptTemplate> createTemplate(@RequestBody @Valid PromptTemplate template) {
        log.info("创建 Prompt 模板请求：name={}", template.getName());
        return R.success(promptTemplateService.createTemplate(template));
    }

    /**
     * 回滚模板版本
     */
    @PostMapping("/rollback")
    public R<PromptTemplate> rollbackTemplate(@RequestBody @Valid PromptRollbackRequest request) {
        log.info("回滚 Prompt 模板请求：name={}, targetVersion={}", request.getName(), request.getTargetVersion());
        return R.success(promptTemplateService.rollbackTemplate(request.getName(), request.getTargetVersion()));
    }
}
