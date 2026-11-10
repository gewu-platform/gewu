package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 会话列表响应 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SessionListResponse {

    private List<com.gewu.module.chat.entity.ChatSession> sessions;

    private Integer total;

    private Integer page;

    private Integer size;

    public static SessionListResponse of(List<com.gewu.module.chat.entity.ChatSession> sessions,
                                            int total, int page, int size) {
        return SessionListResponse.builder()
                .sessions(sessions).total(total).page(page).size(size).build();
    }
}
