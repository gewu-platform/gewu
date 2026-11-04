package com.gewu.module.chat.service;

import com.gewu.common.BizException;
import com.gewu.infrastructure.storage.FileStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.5.0 M1.1：对话附件上传服务测试（mock FileStorage，不依赖真实 MinIO）。
 *
 * 覆盖：格式白名单、MIME 双保险、大小上限、空文件、对象键规则、上传成功回传 URL。
 */
class ChatAttachmentServiceTest {

    private FileStorage storage;
    private ChatAttachmentService svc;

    @BeforeEach
    void setUp() {
        storage = mock(FileStorage.class);
        svc = new ChatAttachmentService(storage);
    }

    private MockMultipartFile img(String name, String contentType, byte[] body) {
        return new MockMultipartFile("file", name, contentType, body);
    }

    @Test
    void 上传成功_返回预签名URL() {
        MockMultipartFile f = img("shot.png", "image/png", "x".getBytes(StandardCharsets.UTF_8));
        when(storage.put(anyString(), any(), anyLong(), anyString())).thenAnswer(i -> i.getArgument(0));
        when(storage.publicUrl(anyString())).thenReturn("http://localhost:19000/gewu/chat/x.png?sig=1");

        String url = svc.uploadImage(f);
        assertThat(url).startsWith("http://localhost:19000/gewu/chat/");
    }

    @Test
    void 对象键按日期分目录且带扩展名() {
        String key = svc.buildKey("jpg");
        assertThat(key).startsWith("chat/").endsWith(".jpg");
        // chat/{yyyyMMdd}/{name}.{ext}
        assertThat(key.split("/")).hasSize(3);
        assertThat(key.split("/")[1]).hasSize(8);
    }

    @Test
    void 拒绝非白名单格式() {
        MockMultipartFile f = img("a.exe", "image/png", new byte[]{1});
        assertThatThrownBy(() -> svc.uploadImage(f))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持的图片格式");
    }

    @Test
    void 拒绝扩展名合规但MIME不是图片() {
        MockMultipartFile f = img("a.png", "text/html", new byte[]{1});
        assertThatThrownBy(() -> svc.uploadImage(f))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不是图片");
    }

    @Test
    void 拒绝空文件() {
        MockMultipartFile f = img("a.png", "image/png", new byte[0]);
        assertThatThrownBy(() -> svc.uploadImage(f))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("文件为空");
    }

    @Test
    void 拒绝超过10MB() {
        byte[] big = new byte[(int) ChatAttachmentService.maxSizeBytes() + 1];
        MockMultipartFile f = img("big.png", "image/png", big);
        assertThatThrownBy(() -> svc.uploadImage(f))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("超过上限");
    }

    @Test
    void 存储异常转业务异常() {
        MockMultipartFile f = img("a.png", "image/png", new byte[]{1});
        when(storage.put(anyString(), any(), anyLong(), anyString()))
                .thenThrow(new FileStorage.StorageException("boom", new RuntimeException("net")));
        assertThatThrownBy(() -> svc.uploadImage(f))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("附件存储失败");
    }

    @Test
    void 扩展名解析忽略大小写且容错无扩展名() {
        assertThat(ChatAttachmentService.extensionOf("A.PNG")).isEqualTo("png");
        assertThat(ChatAttachmentService.extensionOf("noext")).isEmpty();
        assertThat(ChatAttachmentService.extensionOf("trailingdot.")).isEmpty();
        assertThat(ChatAttachmentService.extensionOf(null)).isEmpty();
    }

    @Test
    void 白名单与上限可被文档引用() {
        assertThat(ChatAttachmentService.allowedExtensions())
                .containsExactly("gif", "jpeg", "jpg", "png", "webp");
        assertThat(ChatAttachmentService.maxSizeBytes()).isEqualTo(10L * 1024 * 1024);
    }
}
