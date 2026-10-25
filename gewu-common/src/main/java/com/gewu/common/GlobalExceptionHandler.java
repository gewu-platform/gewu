package com.gewu.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理器（对齐 java-ai-native GlobalExceptionHandler）
 * 捕获并处理所有未处理的异常，统一返回 R 格式
 *
 * @author Matilda
 * @since 2026-10-16
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     */
    @ExceptionHandler(BizException.class)
    public R<Void> handleBizException(BizException e) {
        log.warn("业务异常：code={}, message={}", e.getCode(), e.getMessage());
        return R.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理参数校验异常
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public R<Void> handleIllegalArgument(IllegalArgumentException e) {
        return R.error(400, e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public R<Void> handleValidationException(MethodArgumentNotValidException e) {
        StringBuilder errorMsg = new StringBuilder("参数校验失败：");
        e.getBindingResult().getFieldErrors().forEach(error ->
                errorMsg.append(error.getField())
                        .append("(")
                        .append(error.getDefaultMessage())
                        .append(") "));
        log.warn("参数校验异常：{}", errorMsg);
        return R.error(ErrorCode.BAD_REQUEST.getCode(), errorMsg.toString());
    }

    /**
     * 处理缺少请求参数异常
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public R<Void> handleMissingParameterException(MissingServletRequestParameterException e) {
        log.warn("缺少请求参数：{}", e.getParameterName());
        return R.error(ErrorCode.PARAMETER_MISSING.getCode(),
                "缺少请求参数：" + e.getParameterName());
    }

    /**
     * 处理 404
     */
    @ExceptionHandler(NoHandlerFoundException.class)
    public R<Void> handleNotFoundException(NoHandlerFoundException e) {
        return R.error(ErrorCode.NOT_FOUND.getCode(), "接口不存在：" + e.getRequestURL());
    }

    /**
     * 处理其他未捕获异常
     */
    @ExceptionHandler(Exception.class)
    public R<Void> handleException(Exception e) {
        log.error("系统异常：", e);
        return R.error(ErrorCode.INTERNAL_ERROR.getCode(),
                "系统内部错误，请稍后重试");
    }
}
