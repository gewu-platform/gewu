package com.gewu.module.monitor.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * V2.4.0 C2：审计日志实体
 */
@Data
@TableName("audit_log")
public class AuditLog implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作者 username（匿名/系统为 anonymous 或 system） */
    private String actor;

    /** 动作：agent.create / plugin.install 等 */
    private String action;

    /** 对象类型 */
    private String targetType;

    /** 对象标识 */
    private String targetId;

    /** 变更摘要（已脱敏，不含密钥明文） */
    private String detail;

    /** 来源 IP */
    private String ip;

    /** 1=成功 0=失败 */
    private Integer success;

    private LocalDateTime createTime;
}
