package com.gewu.infrastructure.storage;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * {@link FileStorage} 的 MinIO 实现（S3 兼容，V2.5.0 M1.1.1）。
 *
 * <p>启动时确保桶存在（不存在则自动创建）；MinIO 暂不可达时**不阻断启动**，
 * 仅告警——首次 put 时若仍不可达将抛 {@link StorageException}，
 * 便于「应用先起、存储后起」的开发场景。生产环境 compose 用 depends_on 保证顺序。</p>
 *
 * <p>{@link #publicUrl(String)} 生成预签名 GET URL（默认 7 天有效期，SDK 上限）。
 * 库表存 object key（稳定不变），访问 URL 按需生成——预签名过期不影响重新生成。</p>
 */
@Slf4j
@Component
public class MinioFileStorage implements FileStorage {

    private final StorageProperties props;
    private final MinioClient client;
    /** 预签名专用：endpoint 为对外可达端点（空则与内部端点相同） */
    private final MinioClient urlClient;

    /** 启动期桶检查是否成功（仅用于日志口径；put 时无论如何都会真实探测） */
    private volatile boolean bucketReady = false;

    @Autowired
    public MinioFileStorage(StorageProperties props) {
        this(props, null, null);
    }

    /** 测试专用构造器：注入外部 client（可 mock），null 时按配置自建 */
    MinioFileStorage(StorageProperties props, MinioClient client, MinioClient urlClient) {
        this.props = props;
        this.client = client != null ? client : MinioClient.builder()
                .endpoint(props.getEndpoint())
                .credentials(props.getAccessKey(), props.getSecretKey())
                .build();
        this.urlClient = urlClient != null ? urlClient
                : (props.getPublicEndpoint() == null || props.getPublicEndpoint().isBlank())
                ? this.client
                : MinioClient.builder()
                        .endpoint(props.getPublicEndpoint())
                        .credentials(props.getAccessKey(), props.getSecretKey())
                        .build();
    }

    @PostConstruct
    void ensureBucket() {
        try {
            ensureBucketInternal();
        } catch (Exception e) {
            // 不阻断启动：存储后起时（本地开发常见）应用仍可用，put 时再探测
            bucketReady = false;
            log.warn("[Storage] MinIO 启动检查失败（endpoint={}，bucket={}）：{}。"
                            + "应用继续启动，上传将在 MinIO 可达后自动重试建桶。",
                    props.getEndpoint(), props.getBucket(), e.getMessage());
        }
    }

    private synchronized void ensureBucketInternal() throws Exception {
        boolean exists = client.bucketExists(BucketExistsArgs.builder().bucket(props.getBucket()).build());
        if (!exists) {
            client.makeBucket(MakeBucketArgs.builder().bucket(props.getBucket()).build());
            log.info("[Storage] 已创建桶: {}", props.getBucket());
        }
        bucketReady = true;
    }

    @Override
    public String put(String key, InputStream stream, long size, String contentType) {
        try {
            ensureBucketOnce();
            // size=-1 时启用分片上传（partSize 10MB），由 SDK 缓冲处理未知长度
            long knownSize = size >= 0 ? size : -1;
            long partSize = size >= 0 ? -1 : 10 * 1024 * 1024L;
            PutObjectArgs.Builder b = PutObjectArgs.builder()
                    .bucket(props.getBucket())
                    .object(key)
                    .stream(stream, knownSize, partSize);
            if (contentType != null && !contentType.isBlank()) {
                b.contentType(contentType);
            }
            client.putObject(b.build());
            return key;
        } catch (Exception e) {
            throw new StorageException("存储失败: " + key + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public InputStream get(String key) {
        try {
            return client.getObject(GetObjectArgs.builder()
                    .bucket(props.getBucket()).object(key).build());
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                throw new StorageException("对象不存在: " + key, e);
            }
            throw new StorageException("读取失败: " + key + " (" + e.getMessage() + ")", e);
        } catch (Exception e) {
            throw new StorageException("读取失败: " + key + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public boolean delete(String key) {
        try {
            client.removeObject(RemoveObjectArgs.builder()
                    .bucket(props.getBucket()).object(key).build());
            return true;
        } catch (ErrorResponseException e) {
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            throw new StorageException("删除失败: " + key + " (" + e.getMessage() + ")", e);
        } catch (Exception e) {
            throw new StorageException("删除失败: " + key + " (" + e.getMessage() + ")", e);
        }
    }

    @Override
    public String publicUrl(String key) {
        try {
            return urlClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(io.minio.http.Method.GET)
                    .bucket(props.getBucket())
                    .object(key)
                    .expiry(7, TimeUnit.DAYS)
                    .build());
        } catch (Exception e) {
            throw new StorageException("生成访问 URL 失败: " + key + " (" + e.getMessage() + ")", e);
        }
    }

    /** put 前确保桶可用（启动探测失败后的懒重试路径） */
    private void ensureBucketOnce() throws Exception {
        if (!bucketReady) {
            ensureBucketInternal();
        }
    }
}
