package com.gewu.infrastructure.storage;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V2.5.0 M1.1.1：MinioFileStorage 行为测试（mock client，不依赖真实 MinIO）。
 *
 * 覆盖 M1.1.1 验收要求的上传/下载/删除三条路径：
 * put 成功回传原 key、失败包装 StorageException；
 * get 失败包装；delete 成功返回 true、失败包装。
 * NoSuchKey 细分路径依赖 SDK 异常结构，属集成测试覆盖范围。
 */
class MinioFileStorageTest {

    private MinioClient client;
    private MinioClient urlClient;
    private StorageProperties props;
    private MinioFileStorage storage;

    @BeforeEach
    void setUp() {
        client = mock(MinioClient.class);
        urlClient = mock(MinioClient.class);
        props = new StorageProperties();
        props.setBucket("gewu-test");
        storage = new MinioFileStorage(props, client, urlClient);
    }

    @Test
    void put_成功返回原objectKey() throws Exception {
        when(client.bucketExists(any())).thenReturn(true);
        String key = "knowledge-test-file.pdf";
        try (InputStream in = new ByteArrayInputStream("data".getBytes(StandardCharsets.UTF_8))) {
            String result = storage.put(key, in, 4, "application/pdf");
            assertThat(result).isEqualTo(key);
        }
        verify(client).putObject(any(PutObjectArgs.class));
    }

    @Test
    void put_失败包装为StorageException() throws Exception {
        when(client.bucketExists(any())).thenReturn(true);
        when(client.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("connection refused"));
        try (InputStream in = new ByteArrayInputStream(new byte[0])) {
            assertThatThrownBy(() -> storage.put("k", in, 0, null))
                    .isInstanceOf(FileStorage.StorageException.class)
                    .hasMessageContaining("connection refused");
        }
    }

    @Test
    void get_失败包装为StorageException() throws Exception {
        when(client.getObject(any())).thenThrow(new RuntimeException("timeout"));
        assertThatThrownBy(() -> storage.get("k"))
                .isInstanceOf(FileStorage.StorageException.class)
                .hasMessageContaining("timeout");
    }

    @Test
    void delete_成功返回true() throws Exception {
        boolean r = storage.delete("knowledge/x.pdf");
        assertThat(r).isTrue();
        verify(client).removeObject(any());
    }

    @Test
    void delete_失败包装为StorageException() throws Exception {
        org.mockito.Mockito.doThrow(new RuntimeException("denied"))
                .when(client).removeObject(any());
        assertThatThrownBy(() -> storage.delete("k"))
                .isInstanceOf(FileStorage.StorageException.class)
                .hasMessageContaining("denied");
    }

    @Test
    void publicUrl_使用urlClient生成预签名() throws Exception {
        when(urlClient.getPresignedObjectUrl(any())).thenReturn("http://localhost:19000/gewu-test/k?X-Amz-Signature=x");
        String url = storage.publicUrl("k");
        assertThat(url).startsWith("http://localhost:19000/");
        verify(urlClient).getPresignedObjectUrl(any());
    }

    @Test
    void 启动探测失败时put前会懒重试建桶() throws Exception {
        // 构造时 bucketReady=false；模拟 bucketExists 先 false 后 true，makeBucket 不抛错
        when(client.bucketExists(any())).thenReturn(false, true);
        try (InputStream in = new ByteArrayInputStream(new byte[0])) {
            storage.put("k", in, 0, null);
        }
        verify(client).makeBucket(any());
    }
}
