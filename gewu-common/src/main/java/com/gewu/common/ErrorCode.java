package com.gewu.common;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * API 错误码定义（对齐 java-ai-native ApiErrorCode）
 *
 * @author Matilda
 * @since 2026-10-16
 */
@Getter
@AllArgsConstructor
public enum ErrorCode {

    // 通用错误
    SUCCESS("操作成功", 200),
    INTERNAL_ERROR("内部错误", 500),
    BAD_REQUEST("请求参数错误", 400),
    UNAUTHORIZED("未授权", 401),
    FORBIDDEN("禁止访问", 403),
    NOT_FOUND("资源不存在", 404),
    METHOD_NOT_ALLOWED("方法不允许", 405),

    // API 相关错误
    API_KEY_MISSING("API Key 未配置", 500),
    API_CALL_FAILED("API 调用失败", 500),
    API_RESPONSE_ERROR("API 响应错误", 500),
    API_RATE_LIMIT("API 调用频率超限", 429),
    API_TIMEOUT("API 调用超时", 504),

    // 会话相关错误
    SESSION_NOT_FOUND("会话不存在", 404),
    SESSION_EXPIRED("会话已过期", 401),

    // 多模态相关错误
    IMAGE_URL_INVALID("图片 URL 无效", 400),
    IMAGE_DOWNLOAD_FAILED("图片下载失败", 500),
    UNSUPPORTED_IMAGE_FORMAT("不支持的图片格式", 400),

    // 知识库相关错误
    DOCUMENT_NOT_FOUND("文档不存在", 404),
    DOCUMENT_PROCESS_FAILED("文档处理失败", 500),
    EMBEDDING_FAILED("向量化失败", 500),

    // 模型相关错误
    MODEL_NOT_AVAILABLE("模型不可用", 503),
    MODEL_CONFIG_ERROR("模型配置错误", 500),

    // 参数相关错误
    PARAMETER_INVALID("参数无效", 400),
    PARAMETER_MISSING("参数缺失", 400),
    PARAMETER_OUT_OF_RANGE("参数超出范围", 400);

    /**
     * 错误消息
     */
    private final String message;

    /**
     * 错误码（对应 HTTP 状态码）
     */
    private final int code;
}
