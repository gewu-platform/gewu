package com.example.lc4j.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 模式状态 DTO（对齐 java-ai-native ModeStatusDTO）
 * LangChain4j 版：当前只支持 text 模式，multimodal 预留
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ModeStatusDTO {

    /** 当前模式：text / multimodal */
    private String mode;

    /** 是否多模态 */
    private Boolean multimodal;

    /** 当前模式下的模型 */
    private String currentModel;

    public static ModeStatusDTO of(String mode, boolean multimodal, String currentModel) {
        return ModeStatusDTO.builder()
                .mode(mode).multimodal(multimodal).currentModel(currentModel).build();
    }
}
