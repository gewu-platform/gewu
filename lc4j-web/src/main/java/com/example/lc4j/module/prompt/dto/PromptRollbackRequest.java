package com.example.lc4j.module.prompt.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * Prompt 模板版本回滚请求（对齐 java-ai-native RollbackRequest）
 */
@Data
public class PromptRollbackRequest {

    /**
     * 模板名称
     */
    @NotBlank(message = "模板名称不能为空")
    private String name;

    /**
     * 目标版本号
     */
    @NotNull(message = "目标版本号不能为空")
    private Integer targetVersion;
}
