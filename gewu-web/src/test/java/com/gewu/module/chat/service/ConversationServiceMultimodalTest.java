package com.gewu.module.chat.service;

import com.gewu.client.LangChain4jConfig;
import com.gewu.infrastructure.storage.FileStorage;
import com.gewu.module.monitor.service.LlmMonitorService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.5.0 M1.2：多模态消息构造测试（mock FileStorage，不依赖真实 MinIO 与模型）。
 *
 * 重点验证「为何必须 base64」的实现口径、MIME 推导、以及图片 key 落库的 JSON 序列化。
 * 真实模型调用属集成测试范围（需视觉模型与网络）。
 */
class ConversationServiceMultimodalTest {

    private ConversationService newService(FileStorage storage) {
        return new ConversationService(
                mock(LangChain4jConfig.class),
                mock(ChatSessionService.class),
                mock(ChatMessageService.class),
                mock(TokenUsageService.class),
                mock(LlmMonitorService.class),
                storage);
    }

    @Test
    void mimeType按扩展名推导() {
        assertThat(ConversationService.mimeTypeOf("chat/20261104/a.png")).isEqualTo("image/png");
        assertThat(ConversationService.mimeTypeOf("chat/20261104/a.webp")).isEqualTo("image/webp");
        assertThat(ConversationService.mimeTypeOf("chat/20261104/a.gif")).isEqualTo("image/gif");
        assertThat(ConversationService.mimeTypeOf("chat/20261104/a.jpg")).isEqualTo("image/jpeg");
        assertThat(ConversationService.mimeTypeOf("chat/20261104/a.jpeg")).isEqualTo("image/jpeg");
        // 大小写不敏感
        assertThat(ConversationService.mimeTypeOf("chat/A.PNG")).isEqualTo("image/png");
        // 未知扩展名回落 jpeg（调用方已由白名单保证不会走到）
        assertThat(ConversationService.mimeTypeOf("chat/a.bin")).isEqualTo("image/jpeg");
    }

    @Test
    void json数组序列化() {
        assertThat(ConversationService.toJsonArray(null)).isNull();
        assertThat(ConversationService.toJsonArray(java.util.List.of())).isNull();
        assertThat(ConversationService.toJsonArray(java.util.List.of("chat/a.png")))
                .isEqualTo("[\"chat/a.png\"]");
        assertThat(ConversationService.toJsonArray(java.util.List.of("a", "b")))
                .isEqualTo("[\"a\",\"b\"]");
    }

    @Test
    void json数组转义引号与反斜杠() {
        String out = ConversationService.toJsonArray(java.util.List.of("a\"b", "c\\d"));
        assertThat(out).isEqualTo("[\"a\\\"b\",\"c\\\\d\"]");
    }

    @Test
    void 无图片时构造纯文本消息() {
        FileStorage storage = mock(FileStorage.class);
        ConversationService svc = newService(storage);
        var msg = svc.buildUserMessage("你好", null);
        assertThat(msg.hasSingleText()).isTrue();
        assertThat(msg.singleText()).isEqualTo("你好");
    }

    @Test
    void 有图片时构造图文混合消息() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        when(storage.get(anyString())).thenAnswer(i ->
                new ByteArrayInputStream("PNGDATA".getBytes(StandardCharsets.UTF_8)));
        ConversationService svc = newService(storage);

        var msg = svc.buildUserMessage("看图", java.util.List.of("chat/x.png"));
        // 图文混合：不再有单文本
        assertThat(msg.hasSingleText()).isFalse();
        assertThat(msg.contents()).hasSize(2); // TextContent + ImageContent
        assertThat(msg.contents().get(0)).isInstanceOf(
                dev.langchain4j.data.message.TextContent.class);
        assertThat(msg.contents().get(1)).isInstanceOf(
                dev.langchain4j.data.message.ImageContent.class);
    }

    @Test
    void 单张图片读取失败时降级为仅文本() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        when(storage.get(anyString())).thenThrow(
                new FileStorage.StorageException("not found", new RuntimeException("NoSuchKey")));
        ConversationService svc = newService(storage);

        var msg = svc.buildUserMessage("看图", java.util.List.of("chat/missing.png"));
        // 图片失败被跳过，但文本仍在（对话不中断）
        assertThat(msg.contents()).hasSize(1);
        assertThat(msg.contents().get(0)).isInstanceOf(
                dev.langchain4j.data.message.TextContent.class);
    }

    @Test
    void 空key被跳过() {
        FileStorage storage = mock(FileStorage.class);
        ConversationService svc = newService(storage);
        var msg = svc.buildUserMessage("hi", java.util.List.of("", "  "));
        assertThat(msg.contents()).hasSize(1);
    }
}
