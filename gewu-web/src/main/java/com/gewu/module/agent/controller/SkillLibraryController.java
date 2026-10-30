package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.skill.SkillLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * V2.0.0 R2/T2.3：公共 Skill 库管理 Controller
 *
 * <p>端点：/api/settings/skills（GET 列表）、/api/settings/skills/rescan（POST 手动重扫）。</p>
 *
 * <p>认证：挂在 /api/settings/** 下，SecurityConfig hasRole("ADMIN") 覆盖（对齐 McpServerController）。</p>
 *
 * <p>安全边界：仅读取目录与文件内容作为提示词展示，不执行 SKILL.md 中任何指令。</p>
 */
@Slf4j
@RestController
@RequestMapping("/api/settings/skills")
@RequiredArgsConstructor
public class SkillLibraryController {

    private final SkillLibraryService skillLibrary;

    /** Skill 列表（首次访问触发扫描；目录不存在返回空列表） */
    @GetMapping
    public R<List<SkillLibraryService.SkillMeta>> list() {
        return R.success(skillLibrary.list());
    }

    /**
     * 手动重扫目录（增删 SKILL.md 后生效）
     *
     * @return { count=解析成功数, dir=当前生效目录 }
     */
    @PostMapping("/rescan")
    public R<Map<String, Object>> rescan() {
        int count = skillLibrary.rescan();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", count);
        data.put("dir", skillLibrary.getSkillsDir());
        log.info("[Skill] 手动重扫：count={}, dir={}", count, skillLibrary.getSkillsDir());
        return R.success(data);
    }
}
