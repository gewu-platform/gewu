package com.gewu.infrastructure.storage;

import java.io.InputStream;

/**
 * 文件存储抽象（V2.5.0 M1.1.1）。
 *
 * <p>统一约定：所有上传文件（知识库文档、对话附件）一律写入对象存储；
 * 数据库仅存 object key（访问链接由实现方生成），不存文件本体。</p>
 *
 * <p>方法语义围绕知识库的三条真实消费路径设计：
 * 上传落盘（saveDocument）、解析读流（extractText）、删除（deleteDocument）。</p>
 *
 * <p>当前实现为 {@link MinioFileStorage}；接口保留以便将来替换实现。</p>
 */
public interface FileStorage {

    /**
     * 存储一个文件。
     *
     * @param key        对象键（如 knowledge/1730000000000_report.pdf、chat/1730.../img.png）
     * @param stream     文件内容流（实现方负责关闭）
     * @param size       字节数（-1 表示未知，由实现方自行缓冲确定）
     * @param contentType MIME 类型（可为 null）
     * @return 生成的 object key（与入参一致；保留返回值以便实现方内部重写键名）
     * @throws StorageException 存储失败（网络/权限/桶不可用）
     */
    String put(String key, InputStream stream, long size, String contentType);

    /**
     * 打开一个对象的读取流（解析文档用）。调用方负责关闭流。
     *
     * @param key 对象键
     * @return 对象内容流
     * @throws StorageException 对象不存在或读取失败
     */
    InputStream get(String key);

    /**
     * 删除一个对象（文档物理删除用）。对象不存在时静默返回 false，不视为错误。
     *
     * @param key 对象键
     * @return true=确实删除了对象；false=对象本就不存在
     * @throws StorageException 删除操作本身失败（网络/权限）
     */
    boolean delete(String key);

    /**
     * 生成对象的对外访问 URL（库表 filePath 字段存这个值）。
     * 实现可为预签名 URL（带过期）或公共端点直链，由实现决定。
     *
     * @param key 对象键
     * @return 可访问的 URL 字符串
     */
    String publicUrl(String key);

    /** 存储操作异常（统一包装底层 SDK 异常，避免调用方耦合 MinIO 类型） */
    class StorageException extends RuntimeException {
        public StorageException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
