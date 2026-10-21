package com.example.lc4j.module.agent.tools;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 网页搜索工具（对齐 java-ai-native SearchTool 占位）
 *
 * 未配置搜索 API Key：返回友好提示引导走审批流程演示。
 * 审批网关会拦截本工具（外部网络请求需审批）。
 */
@Slf4j
@Component
public class SearchTool {

    @Tool(name = "search", value = "搜索互联网获取最新信息。当用户询问实时新闻、天气等需要联网查询的问题时使用。当前为占位实现，未接入真实搜索 API。")
    public String search(
            @P("搜索关键词或问题")
            String query) {
        log.debug("search 执行（占位）：{}", query);
        return "[搜索占位] 未配置搜索 API Key，无法执行真实搜索。查询内容：" + query
                + "。请联系管理员配置搜索服务，或改用其他方式获取信息。";
    }
}
