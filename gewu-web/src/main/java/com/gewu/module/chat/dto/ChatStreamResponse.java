package com.gewu.module.chat.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 流式聊天响应 DTO
 * SSE 事件体：chunk / done / error 三种形态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatStreamResponse {

    private String type;

    private String sessionId;

    /** chunk 事件：增量内容 */
    private String chunk;

    /** done 事件：完整回复 */
    private String reply;

    private String model;

    private Integer inputTokens;

    private Integer outputTokens;

    /** error 事件：错误信息 */
    private String error;

    public static ChatStreamResponse chunk(String sessionId, String chunk) {
        return ChatStreamResponse.builder()
                .type("chunk").sessionId(sessionId).chunk(chunk).build();
    }

    public static ChatStreamResponse done(String sessionId, String reply, String model,
                                             int inputTokens, int outputTokens) {
        return ChatStreamResponse.builder()
                .type("done").sessionId(sessionId).reply(reply).model(model)
                .inputTokens(inputTokens).outputTokens(outputTokens).build();
    }

    public static ChatStreamResponse error(String sessionId, String error) {
        return ChatStreamResponse.builder()
                .type("error").sessionId(sessionId).error(error).build();
    }
}
