package com.gewu.module.plugin.controller;

import com.gewu.common.R;
import com.gewu.module.plugin.entity.PluginRegistry;
import com.gewu.module.plugin.service.PluginRegistryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * V2.0.0 R3/T3.2：插件仓库管理 Controller
 *
 * <p>端点：/api/settings/plugins（GET 列表 / POST 新增自定义条目）、
 * /api/settings/plugins/{id}/install（POST 一键安装）、
 * /api/settings/plugins/{id}/uninstall（POST 卸载）、
 * /api/settings/plugins/{id}（DELETE 删除条目）。</p>
 *
 * <p>认证：挂在 /api/settings/** 下，SecurityConfig hasRole("ADMIN") 覆盖（对齐 McpServerController）。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/plugins")
@RequiredArgsConstructor
public class PluginRegistryController {

    private final PluginRegistryService pluginRegistryService;

    /** 插件清单（内置样例自动灌入） */
    @GetMapping
    public R<List<PluginRegistry>> list() {
        return R.success(pluginRegistryService.list());
    }

    /** 新增自定义插件条目 */
    @PostMapping
    public R<PluginRegistry> create(@RequestBody PluginRegistry req) {
        return R.success(pluginRegistryService.create(req));
    }

    /** 一键安装（登记进 mcp_server 并桥接工具） */
    @PostMapping("/{id}/install")
    public R<Map<String, Object>> install(@PathVariable Long id) {
        return R.success(pluginRegistryService.install(id));
    }

    /** 卸载（删除 mcp_server 登记并断开桥接） */
    @PostMapping("/{id}/uninstall")
    public R<Map<String, Object>> uninstall(@PathVariable Long id) {
        return R.success(pluginRegistryService.uninstall(id));
    }

    /** 删除条目（内置不可删；已安装需先卸载） */
    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        pluginRegistryService.delete(id);
        return R.success();
    }
}
