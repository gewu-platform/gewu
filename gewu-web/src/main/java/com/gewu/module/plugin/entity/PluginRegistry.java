package com.gewu.module.plugin.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * V2.0.0 R3/T3.2：插件仓库登记实体
 *
 * <p>首版插件形态 = 外部 MCP server（复用 V1.1.0 C3 客户端）。
 * 注册表只维护「清单」，一键安装即把条目登记进 mcp_server 并桥接工具。</p>
 */
@Data
@TableName("plugin_registry")
public class PluginRegistry implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 插件编码（仓库内唯一，如 filesystem / fetch / time） */
    private String code;

    /** 插件名称 */
    private String name;

    /** 插件描述 */
    private String description;

    /** 传输类型：http_sse（首版唯一支持） */
    private String transport;

    /** 插件端点（SSE URL） */
    private String endpoint;

    /** 工具白名单 JSON 数组；空=不限 */
    private String toolAllow;

    /** 内置样例：true=系统预置不可删除 */
    private Boolean builtin;

    /** 安装状态：true=已登记进 mcp_server */
    private Boolean installed;

    /** 安装后的 mcp_server 名称（用于反查与卸载） */
    private String installedName;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
