package com.gewu.infrastructure.storage;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 文件存储配置（V2.5.0 M1.1.1）。
 *
 * <p>统一约定：所有上传文件（知识库文档、对话附件）一律写入 MinIO；
 * 数据库仅存文件访问链接（URL / object key），不存文件本体。
 * MinIO 为 S3 兼容自部署服务，与「数据存本地」的信任声明一致。</p>
 *
 * <p>配置来源：application.yml 的 {@code gewu.storage.*}，可被
 * {@code GEWU_STORAGE_*} 环境变量覆盖（yml 占位符已预留）。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "gewu.storage")
public class StorageProperties {

    /** MinIO 端点（含端口），如 http://localhost:9000；容器内为 http://minio:9000 */
    private String endpoint = "http://localhost:9000";

    /** 桶名；不存在时启动自动创建 */
    private String bucket = "gewu";

    /** 访问密钥（生产必须通过环境变量注入，不入库不落日志） */
    private String accessKey = "";

    /** 私有密钥（同上） */
    private String secretKey = "";

    /**
     * 预签名 URL 用的对外端点（浏览器可达）；空 = 与 endpoint 相同。
     * 容器部署时应用内用容器网络端点（http://gewu-minio:9000），
     * 而预签名 URL 需宿主机浏览器可达（http://localhost:19000）——两个端点必须分开。
     * 签名只做本地计算不需要连通性，因此用第二个 client 即可。
     */
    private String publicEndpoint = "";
}
