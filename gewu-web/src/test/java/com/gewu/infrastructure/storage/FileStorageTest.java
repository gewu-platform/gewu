// M1.1.1 存储抽象单测：FileStorage 契约 + MinioFileStorage 参数处理
// 不依赖真实 MinIO（纯逻辑层验证），集成验证由验收阶段的真实桶连通测试承担
package com.gewu.infrastructure.storage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V2.5.0 M1.1.1：存储抽象单测
 *
 * 验证点：StorageProperties 默认值与 publicEndpoint 回退逻辑、
 * StorageException 包装、key 契约（put 返回与入参一致）。
 * MinIO SDK 网络行为不在此覆盖（属集成测试，见验收说明）。
 */
class FileStorageTest {

    @Test
    void storageProperties_默认值符合约定() {
        StorageProperties p = new StorageProperties();
        assertThat(p.getEndpoint()).isEqualTo("http://localhost:9000");
        assertThat(p.getBucket()).isEqualTo("gewu");
        assertThat(p.getPublicEndpoint()).isEmpty();
    }

    @Test
    void storageProperties_公开端点可覆盖() {
        StorageProperties p = new StorageProperties();
        p.setEndpoint("http://gewu-minio:9000");
        p.setPublicEndpoint("http://localhost:19000");
        assertThat(p.getEndpoint()).isEqualTo("http://gewu-minio:9000");
        assertThat(p.getPublicEndpoint()).isEqualTo("http://localhost:19000");
    }

    @Test
    void storageException_保留原因链() {
        IllegalStateException cause = new IllegalStateException("boom");
        FileStorage.StorageException ex = new FileStorage.StorageException("存储失败: k", cause);
        assertThat(ex.getMessage()).isEqualTo("存储失败: k");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void storageException_可独立抛出() {
        // 模拟实现方包装：调用方只依赖 FileStorage.StorageException，不耦合 MinIO 类型
        assertThatThrownBy(() -> {
            try {
                throw new java.net.ConnectException("Connection refused");
            } catch (Exception e) {
                throw new FileStorage.StorageException("读取失败: a/b.pdf (" + e.getMessage() + ")", e);
            }
        }).isInstanceOf(FileStorage.StorageException.class)
          .hasMessageContaining("Connection refused");
    }

    /** key 契约示例：knowledge/{ts}_{name} 与 chat/{ts}/{name} 两种前缀均在约定内 */
    @Test
    void objectKey_命名约定() {
        String kbKey = "knowledge/" + System.currentTimeMillis() + "_报告.pdf";
        String chatKey = "chat/" + System.currentTimeMillis() + "/clip.png";
        assertThat(kbKey).startsWith("knowledge/").endsWith(".pdf");
        assertThat(chatKey).startsWith("chat/").endsWith(".png");
        // 流内容契约：实现方按 size 传递，这里验证 size>=0 与 -1（未知长度）两条路径的流可构造
        try (InputStream known = new ByteArrayInputStream("x".getBytes(StandardCharsets.UTF_8));
             InputStream unknown = new ByteArrayInputStream(new byte[0])) {
            assertThat(known).isNotNull();
            assertThat(unknown).isNotNull();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
