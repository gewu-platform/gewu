package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模式状态 DTO
 * LangChain4j 版：当前只支持 text 模式，multimodal 预留
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModeStatusView {

    /** 当前模式：text / multimodal */
    private String mode;

    /** 是否多模态 */
    private Boolean multimodal;

    /** 当前模式下的模型 */
    private String currentModel;

    public static ModeStatusView of(String mode, boolean multimodal, String currentModel) {
        return ModeStatusView.builder()
                .mode(mode).multimodal(multimodal).currentModel(currentModel).build();
    }
}
