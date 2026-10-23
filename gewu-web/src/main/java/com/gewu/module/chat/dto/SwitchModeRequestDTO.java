package com.gewu.module.chat.dto;

import lombok.Data;

/**
 * 模式切换请求 DTO（对齐 java-ai-native SwitchModeRequestDTO）
 */
@Data
public class SwitchModeRequestDTO {

    /**
     * 是否切换到多模态模式
     */
    private Boolean multimodal;
}
