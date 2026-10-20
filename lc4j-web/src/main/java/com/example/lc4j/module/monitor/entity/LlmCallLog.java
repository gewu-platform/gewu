package com.example.lc4j.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * LLM 调用日志实体（对齐 java-ai-native LlmCallLog）
 */
@Data
@TableName("llm_call_log")
public class LlmCallLog implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键 ID
     */
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 模型名称
     */
    private String modelName;

    /**
     * 使用的模板名称（可为空）
     */
    private String templateName;

    /**
     * 输入 token 数
     */
    private Integer promptTokens;

    /**
     * 输出 token 数
     */
    private Integer completionTokens;

    /**
     * 总 token 数
     */
    private Integer totalTokens;

    /**
     * 估算费用（元）
     */
    private BigDecimal cost;

    /**
     * 响应延迟（毫秒）
     */
    private Long latency;

    /**
     * 状态：success/error/timeout
     */
    private String status;

    /**
     * 错误信息（可为空）
     */
    private String errorMessage;

    /**
     * 请求摘要（前200字符）
     */
    private String requestSummary;

    /**
     * 响应摘要（前500字符）
     */
    private String responseSummary;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 创建时间（表默认 CURRENT_TIMESTAMP；本项目无 MetaObjectHandler，插入时不设置由库填默认值）
     */
    private LocalDateTime createTime;
}
