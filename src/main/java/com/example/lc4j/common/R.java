package com.example.lc4j.common;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 统一响应包装器（对齐 java-ai-native Result）
 *
 * @param <T> 响应数据类型
 * @author Matilda
 * @since 2026-10-16
 */
@Data
public class R<T> {

    /**
     * 响应码
     */
    private Integer code;

    /**
     * 响应消息
     */
    private String message;

    /**
     * 响应数据
     */
    private T data;

    /**
     * 时间戳
     */
    private LocalDateTime timestamp;

    public R() {
        this.timestamp = LocalDateTime.now();
    }

    /**
     * 成功响应（带数据）
     */
    public static <T> R<T> success(T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMessage("success");
        r.setData(data);
        return r;
    }

    /**
     * 成功响应（带消息和数据）
     */
    public static <T> R<T> success(String message, T data) {
        R<T> r = new R<>();
        r.setCode(200);
        r.setMessage(message);
        r.setData(data);
        return r;
    }

    /**
     * 成功响应（无数据）
     */
    public static R<Void> success() {
        return success(null);
    }

    /**
     * 错误响应（带错误码和消息）
     */
    public static <T> R<T> error(Integer code, String message) {
        R<T> r = new R<>();
        r.setCode(code);
        r.setMessage(message);
        return r;
    }

    /**
     * 错误响应（使用 ErrorCode 枚举）
     */
    public static <T> R<T> error(ErrorCode errorCode) {
        return error(errorCode.getCode(), errorCode.getMessage());
    }

    /**
     * 错误响应（默认 500）
     */
    public static <T> R<T> error(String message) {
        return error(ErrorCode.INTERNAL_ERROR.getCode(), message);
    }
}
