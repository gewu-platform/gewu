package com.gewu.module.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 聊天请求 DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatRequest {

    /**
     * 会话 ID（为空则自动创建新会话）
     */
    private String sessionId;

    /**
     * 用户消息
     */
    private String message;

    /**
     * 是否使用多模态模式（预留，P3 先落 API 契约）
     */
    private Boolean useMultimodal;

    /**
     * 图片 URL 列表（前端展示用，来自 /upload 返回的预签名 URL）
     */
    private java.util.List<String> imageUrls;

    /**
     * V2.5.0 M1.2：图片 object key 列表（后端消费用）。
     *
     * <p>为何不直接用 imageUrls：预签名 URL 指向 self-host 的 MinIO，
     * 云端视觉模型无法访问该地址，必须由后端读出字节以 base64 传入。
     * 前端提交时把 /upload 返回的 key 回填到此字段。</p>
     */
    private java.util.List<String> imageKeys;

    /**
     * V2.5.0 M2：文档 object key 列表（对话内文件解析）。
     *
     * <p>与图片不同，文档不走图像通道——后端取出文件后复用
     * DocumentParseService 抽取文本，作为上下文附加到本次用户消息之前。
     * 文本不落库、不进向量库（不是知识库文档）。</p>
     */
    private java.util.List<String> fileKeys;

    /**
     * 系统提示词（可选）
     */
    private String systemPrompt;

    /**
     * 指定模型 ID（可选，为空用当前激活模型）
     */
    private String model;
}
