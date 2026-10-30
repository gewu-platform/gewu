package com.gewu.module.agent.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.gewu.common.mybatis.JsonbTypeHandler;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * V1.1.0 C2/T2.1：Agent 执行态持久化（checkpoint）
 * 当前用途：审批挂起态落库（waiting_approval），重启后可恢复；后续扩展到更多执行态。
 */
@Data
@TableName("agent_execution")
public class AgentExecution implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 主键 = toolCallId（天然幂等） */
    @TableId(type = IdType.INPUT)
    private String id;

    private String sessionId;

    private String agentId;

    /** waiting_approval / approved / rejected / expired */
    private String status;

    /** 状态快照 JSON（toolCallId/toolName/toolDescription/params 等） */
    @TableField(typeHandler = JsonbTypeHandler.class)
    private String stateJson;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
