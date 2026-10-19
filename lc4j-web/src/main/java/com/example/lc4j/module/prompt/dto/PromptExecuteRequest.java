package com.example.lc4j.module.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * Prompt 模板执行请求（对齐 java-ai-native PromptExecuteRequest）
 */
@Data
public class PromptExecuteRequest {

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    private String templateName;

    /**
     * 模板变量（{variable} 占位符 → 值）
     */
    private Map<String, String> variables;
}
