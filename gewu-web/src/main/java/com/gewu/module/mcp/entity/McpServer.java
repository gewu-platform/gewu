package com.gewu.module.mcp.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * V1.1.0 C3/T3.2：MCP 服务器配置实体
 *
 * <p>时间字段由表默认值维护（insert），update 路径手动 set updateTime（无 MetaObjectHandler）。</p>
 */
@Data
@TableName("mcp_server")
public class McpServer implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置名称（唯一） */
    private String name;

    /** 传输类型 - http_sse（首版唯一支持） */
    private String transport;

    /** MCP server 端点（SSE URL） */
    private String endpoint;

    /** 启用状态 */
    private Boolean enabled;

    /** 备注（工具用途说明等） */
    private String remark;
    /** v2.0.1 B1：工具白名单（JSON 数组或逗号分隔；空=不限） */
    private String toolAllow;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
