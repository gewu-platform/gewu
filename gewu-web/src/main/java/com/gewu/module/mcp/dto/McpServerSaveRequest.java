package com.gewu.module.mcp.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * V1.1.0 C3/T3.2：MCP 服务器保存请求
 */
@Data
public class McpServerSaveRequest {

    @NotBlank(message = "配置名称不能为空")
    @Size(max = 64, message = "配置名称过长（≤64 字符）")
    private String name;

    /** 缺省 http_sse */
    @Size(max = 20)
    private String transport;

    @NotBlank(message = "端点不能为空")
    @Size(max = 500, message = "端点过长（≤500 字符）")
    private String endpoint;

    private Boolean enabled;

    @Size(max = 255)
    private String remark;

    // v2.0.1 B1：工具白名单（JSON 数组字符串或逗号分隔；空=不限）
    private String toolAllow;
}
