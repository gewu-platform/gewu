package com.gewu.module.prompt.dto;

import lombok.Data;

/**
 * Prompt 模板执行响应（对齐 java-ai-native PromptExecuteResponse）
 */
@Data
public class PromptExecuteResponse {

    /**
     * AI 回复内容
     */
    private String content;

    /**
     * 模板名称
     */
    private String templateName;

    /**
     * 执行所用模型
     */
    private String model;

    /**
     * 估算 token 总数（简化：字符数 / 4）
     */
    private Integer tokens;

    /**
     * 执行耗时（毫秒）
     */
    private Long duration;
}
