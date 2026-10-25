package com.gewu.module.setting.controller;

import com.gewu.common.R;
import com.gewu.module.setting.dto.LlmProviderSaveRequest;
import com.gewu.module.setting.dto.LlmProviderView;
import com.gewu.module.setting.service.LlmProviderService;
import com.gewu.module.setting.service.ProviderConnectivityService;
import jakarta.validation.Valid;
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
 * 模型配置中心 Controller（T3）
 *
 * <p>端点：/api/settings/providers（GET 列表 / POST 新增）、
 * /api/settings/providers/{id}（PUT 更新 / DELETE 删除）、
 * /api/settings/providers/{id}/activate（POST 激活）、
 * /api/settings/providers/{id}/test（POST 连通性测试）。</p>
 *
 * <p>认证：SecurityConfig anyRequest().authenticated() 覆盖；管理面接口与既有
 * /api/agent/definitions/** 同级，如需收紧为 ADMIN 在 SecurityConfig 追加一行匹配即可。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings")
@RequiredArgsConstructor
public class LlmProviderController {

    private final LlmProviderService llmProviderService;
    private final ProviderConnectivityService connectivityService;

    /**
     * 配置列表
     */
    @GetMapping("/providers")
    public R<List<LlmProviderView>> list() {
        return R.success(llmProviderService.listAll());
    }

    /**
     * 新增配置
     */
    @PostMapping("/providers")
    public R<LlmProviderView> create(@RequestBody @Valid LlmProviderSaveRequest request) {
        return R.success(llmProviderService.create(request));
    }

    /**
     * 更新配置
     */
    @PostMapping("/providers/{id}")
    public R<LlmProviderView> update(@PathVariable Long id,
                                     @RequestBody @Valid LlmProviderSaveRequest request) {
        return R.success(llmProviderService.update(id, request));
    }

    /**
     * 删除配置
     */
    @DeleteMapping("/providers/{id}")
    public R<Void> delete(@PathVariable Long id) {
        llmProviderService.delete(id);
        return R.success(null);
    }

    /**
     * 激活配置
     */
    @PostMapping("/providers/{id}/activate")
    public R<Void> activate(@PathVariable Long id) {
        llmProviderService.activate(id);
        return R.success(null);
    }

    /**
     * 连通性测试：chat 测微型补全；embedding 测向量调用并回报维度。
     * 测试针对「当前保存的配置」，不影响激活状态。
     */
    @PostMapping("/providers/{id}/test")
    public R<Map<String, Object>> test(@PathVariable Long id) {
        return R.success(connectivityService.test(id));
    }
}
