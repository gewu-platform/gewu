package com.gewu.module.chat.service;

import com.gewu.common.BizException;
import com.gewu.infrastructure.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 对话附件上传服务（V2.5.0 M1.1）。
 *
 * <p>职责边界：只负责「校验 → 写入存储 → 返回可访问链接」，不碰消息落库或模型调用
 * （那属 M1.2 链路消费）。</p>
 *
 * <p>对象键规则：{@code chat/{yyyyMMdd}/{uuid}.{ext}}，按日期分目录便于生命周期管理；
 * 不使用原始文件名（避免中文/特殊字符与重名）。</p>
 *
 * <p>安全约束：扩展名白名单 + MIME 前缀校验（双保险，防改扩展名绕过）+ 大小上限。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatAttachmentService {

    /** 允许的图片扩展名（M1 只做图片；文件解析属 M2） */
    private static final Set<String> ALLOWED_EXT = Set.of("jpg", "jpeg", "png", "webp", "gif");

    /** 单文件上限 10MB */
    private static final long MAX_SIZE_BYTES = 10L * 1024 * 1024;

    private final FileStorage fileStorage;

    /**
     * 上传对话图片附件。
     *
     * @param file 上传文件（multipart）
     * @return 可访问 URL（预签名，7 天有效；库表存 object key，此处直接给前端用）
     * @throws BizException 类型/大小不合法
     */
    public String uploadImage(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(400, "文件为空");
        }
        if (file.getSize() > MAX_SIZE_BYTES) {
            throw new BizException(400, "文件超过上限（10MB）");
        }

        String original = file.getOriginalFilename();
        String ext = extensionOf(original);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BizException(400, "不支持的图片格式：" + ext
                    + "（允许 jpg/jpeg/png/webp/gif）");
        }

        String contentType = file.getContentType();
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("image/")) {
            throw new BizException(400, "文件类型不是图片（" + contentType + "）");
        }

        String key = buildKey(ext);
        try (InputStream in = file.getInputStream()) {
            fileStorage.put(key, in, file.getSize(), contentType);
        } catch (IOException e) {
            throw new BizException(500, "读取上传文件失败：" + e.getMessage());
        } catch (FileStorage.StorageException e) {
            log.error("[ChatAttachment] 存储失败 key={}", key, e);
            throw new BizException(500, "附件存储失败，请稍后重试");
        }

        log.info("[ChatAttachment] 上传成功 key={}, size={}, type={}", key, file.getSize(), contentType);
        return fileStorage.publicUrl(key);
    }

    /** 对象键：chat/{yyyyMMdd}/{uuid}.{ext} */
    String buildKey(String ext) {
        String day = java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE);
        // 用 time + random 而非 UUID 去掉横线：短且不依赖额外 import
        String name = Long.toString(System.currentTimeMillis(), 36)
                + Integer.toString(java.util.concurrent.ThreadLocalRandom.current().nextInt(1 << 20), 36);
        return "chat/" + day + "/" + name + "." + ext;
    }

    /** 取扩展名（小写，无点）；无扩展名返回空串 */
    static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    /** 暴露上限供测试与文档引用 */
    public static long maxSizeBytes() {
        return MAX_SIZE_BYTES;
    }

    /** 暴露白名单供测试与文档引用 */
    public static List<String> allowedExtensions() {
        return ALLOWED_EXT.stream().sorted().toList();
    }
}
