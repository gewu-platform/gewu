package com.gewu.module.agent.controller;

import com.gewu.common.R;
import com.gewu.module.agent.skill.SkillLibraryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;

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
     * R2/T2.2：查看当前生效目录（含是否存在且可读）
     */
    @GetMapping("/dir")
    public R<Map<String, Object>> getDir() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dir", skillLibrary.getSkillsDir());
        data.put("readable", skillLibrary.isDirReadable());
        return R.success(data);
    }

    /**
     * R2/T2.2：运行时切换目录并立即重扫。
     * 仅改内存态，不回写 application.yml（重启回退到 yml/env 配置值）。
     *
     * @param body { "dir": "D:\\skills" }
     */
    @PostMapping("/dir")
    public R<Map<String, Object>> setDir(@RequestBody(required = false) Map<String, String> body) {
        String dir = body == null ? null : body.get("dir");
        boolean ok = skillLibrary.setSkillsDir(dir);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("changed", ok);
        data.put("dir", skillLibrary.getSkillsDir());
        data.put("readable", skillLibrary.isDirReadable());
        if (ok) {
            int count = skillLibrary.rescan();
            data.put("count", count);
        } else if (dir != null && !dir.isBlank()) {
            data.put("message", "目录不存在或不可读，未修改");
        } else {
            data.put("message", "目录为空，未修改");
        }
        return R.success(data);
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
