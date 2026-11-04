package com.gewu.module.chat.service;

import com.gewu.client.LangChain4jConfig;
import com.gewu.infrastructure.storage.FileStorage;
import com.gewu.module.knowledge.service.DocumentParseService;
import com.gewu.module.monitor.service.LlmMonitorService;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.5.0 M2：对话内文件解析测试（mock 存储与解析器）。
 *
 * 验证点：无文档返回 null、单文档注入含文件名与正文、体积预算截断、
 * 读取失败降级不抛出、空 key 跳过、文件名提取。
 */
class ConversationServiceDocumentTest {

    private ConversationService newService(FileStorage storage, DocumentParseService parser) {
        return new ConversationService(
                mock(LangChain4jConfig.class),
                mock(ChatSessionService.class),
                mock(ChatMessageService.class),
                mock(TokenUsageService.class),
                mock(LlmMonitorService.class),
                storage,
                parser);
    }

    @Test
    void 无文档返回null() {
        ConversationService svc = newService(mock(FileStorage.class), mock(DocumentParseService.class));
        assertThat(svc.buildDocumentContext(null)).isNull();
        assertThat(svc.buildDocumentContext(List.of())).isNull();
    }

    @Test
    void 单文档注入含文件名与正文() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        when(storage.get(anyString())).thenReturn(
                new ByteArrayInputStream("doc".getBytes(StandardCharsets.UTF_8)));
        when(parser.extractText(anyString(), any())).thenReturn("这是文档正文内容。");

        ConversationService svc = newService(storage, parser);
        String ctx = svc.buildDocumentContext(List.of("chat/files/20261104/abc.pdf"));

        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("参考");                       // 注入块引导语
        assertThat(ctx).contains("abc.pdf");                    // 文件名
        assertThat(ctx).contains("这是文档正文内容。");          // 正文
    }

    @Test
    void 多文档按预算截断() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        when(storage.get(anyString())).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        // 每个文档都给超长文本，总额超过预算
        String huge = "A".repeat(ChatAttachmentService.maxInjectChars());
        when(parser.extractText(anyString(), any())).thenReturn(huge);

        ConversationService svc = newService(storage, parser);
        String ctx = svc.buildDocumentContext(List.of("k/a.pdf", "k/b.pdf", "k/c.pdf"));

        assertThat(ctx).isNotNull();
        // 预算只够第一份（每份都占满预算），后续被跳过
        assertThat(ctx).contains("a.pdf");
        assertThat(ctx).doesNotContain("b.pdf");
    }

    @Test
    void 读取失败降级不抛异常() {
        FileStorage storage = mock(FileStorage.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        when(storage.get(anyString())).thenThrow(
                new FileStorage.StorageException("not found", new RuntimeException("NoSuchKey")));

        ConversationService svc = newService(storage, parser);
        // 单文档失败 → 无有效文档 → 返回 null，且不抛出
        assertThat(svc.buildDocumentContext(List.of("k/missing.pdf"))).isNull();
    }

    @Test
    void 解析结果为空则跳过() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        when(storage.get(anyString())).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(parser.extractText(anyString(), any())).thenReturn("   ");  // 纯空白

        ConversationService svc = newService(storage, parser);
        assertThat(svc.buildDocumentContext(List.of("k/empty.txt"))).isNull();
    }

    @Test
    void 空key被跳过() throws Exception {
        FileStorage storage = mock(FileStorage.class);
        DocumentParseService parser = mock(DocumentParseService.class);
        when(storage.get(anyString())).thenReturn(
                new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8)));
        when(parser.extractText(anyString(), any())).thenReturn("正文");

        ConversationService svc = newService(storage, parser);
        String ctx = svc.buildDocumentContext(List.of("", "  ", "k/real.md"));
        assertThat(ctx).isNotNull();
        assertThat(ctx).contains("real.md");
    }

    @Test
    void 文件名提取() {
        assertThat(ConversationService.fileNameOf("chat/files/20261104/abc.pdf")).isEqualTo("abc.pdf");
        assertThat(ConversationService.fileNameOf("abc.md")).isEqualTo("abc.md");
        assertThat(ConversationService.fileNameOf(null)).isEmpty();
        assertThat(ConversationService.fileNameOf("")).isEmpty();
    }

    @Test
    void 文档注入上限可被引用() {
        assertThat(ChatAttachmentService.maxInjectChars()).isEqualTo(20000);
        assertThat(ChatAttachmentService.allowedDocExtensions())
                .containsExactly("docx", "md", "pdf", "txt");
    }
}
