package com.gewu.module.chat.dto;

import lombok.Data;

/**
 * 模式切换请求 DTO
 */
@Data
public class SwitchModeRequest {

    /**
     * 是否切换到多模态模式
     */
    private Boolean multimodal;
}
