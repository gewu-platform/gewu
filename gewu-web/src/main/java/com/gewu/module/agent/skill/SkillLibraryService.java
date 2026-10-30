package com.gewu.module.agent.skill;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * V2.0.0 R2/T2.1+T2.2：公共 Skill 库
 *
 * <p>扫描默认目录（C:\Users\25333\.agents\skills）下各子目录内的 SKILL.md 文件，
 * 解析 YAML frontmatter（name / description）与正文指令段，
 * 以「提示词包」形态供 Agent 定义勾选启用：对话构建 system prompt 时注入
 * 已启用技能的指令段。</p>
 *
 * <p>安全边界（T2.4）：SKILL.md 内容仅作提示词注入，服务端不解析、不执行其中任何指令；
 * 目录只读（本服务只做 Files.read，从不 write/delete）。损坏文件跳过并告警。</p>
 */
@Slf4j
@Service
public class SkillLibraryService {

    /** Skill 元数据（目录扫描产物，只读） */
    @Data
    public static class SkillMeta {
        /** 目录名即 id（如 caveman） */
        private String id;
        private String name;
        private String description;
        /** 正文指令段（不含 frontmatter） */
        private String instructions;
        /** 来源文件路径（只读展示） */
        private String path;
    }

    /** 扫描缓存：id → 元数据 */
    private final Map<String, SkillMeta> cache = new ConcurrentHashMap<>();

    /** Skill 子目录下的固定文件名 */
    private static final String SKILL_FILE = "SKILL.md";

    @Value("${gewu.skills.dir:${GEWU_SKILLS_DIR:C:\\Users\\25333\\.agents\\skills}}")
    private String skillsDir;

    /**
     * R2/T2.2 运行时改目录：仅改内存态（不回写 application.yml，避免应用写坏自身配置）。
     *
     * @param dir 新目录；null/空白或不存在/不可读时返回 false 且不改值
     * @return 是否切换成功
     */
    public boolean setSkillsDir(String dir) {
        if (dir == null || dir.isBlank()) {
            log.warn("[Skill] 目录为空，忽略修改");
            return false;
        }
        Path p = Paths.get(dir.trim());
        if (!Files.isDirectory(p) || !Files.isReadable(p)) {
            log.warn("[Skill] 目录不存在或不可读，忽略修改：{}", dir);
            return false;
        }
        this.skillsDir = p.toString();
        log.info("[Skill] 目录已切换（运行时）：{}", this.skillsDir);
        return true;
    }

    /** 目录是否存在且可读（T2.4 前端明确提示用） */
    public boolean isDirReadable() {
        try {
            Path p = Paths.get(skillsDir);
            return Files.isDirectory(p) && Files.isReadable(p);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 全量重扫目录（T2.3 手动重扫入口）
     *
     * @return 解析成功的 skill 数；目录不存在或损坏返回 0 并告警
     */
    public int rescan() {
        Path root = Paths.get(skillsDir);
        cache.clear();
        if (!Files.isDirectory(root)) {
            log.warn("[Skill] 目录不存在或不是目录，跳过扫描：{}", skillsDir);
            return 0;
        }
        int ok = 0;
        try (Stream<Path> dirs = Files.list(root)) {
            List<Path> candidates = dirs.filter(Files::isDirectory).toList();
            for (Path dir : candidates) {
                Path file = dir.resolve(SKILL_FILE);
                if (!Files.isRegularFile(file)) {
                    continue;
                }
                try {
                    SkillMeta meta = parse(dir, file);
                    if (meta != null) {
                        cache.put(meta.getId(), meta);
                        ok++;
                    }
                } catch (Exception e) {
                    // T2.1 容忍损坏文件：跳过并告警，不影响整库
                    log.warn("[Skill] 解析失败已跳过：{}，原因={}", file, e.getMessage());
                }
            }
        } catch (IOException e) {
            log.warn("[Skill] 目录扫描失败：{}，原因={}", skillsDir, e.getMessage());
        }
        log.info("[Skill] 扫描完成：目录={}，解析成功={}", skillsDir, ok);
        return ok;
    }

    /** 列表（按 id 排序） */
    public List<SkillMeta> list() {
        if (cache.isEmpty()) {
            rescan();
        }
        List<SkillMeta> all = new ArrayList<>(cache.values());
        all.sort((a, b) -> String.CASE_INSENSITIVE_ORDER.compare(
                a.getId() == null ? "" : a.getId(), b.getId() == null ? "" : b.getId()));
        return Collections.unmodifiableList(all);
    }

    /** 取单个（未命中返回 null） */
    public SkillMeta get(String id) {
        if (id == null) {
            return null;
        }
        return cache.get(id);
    }

    /**
     * T2.2：把已启用技能指令段追加到 system prompt 尾部。
     *
     * @param base          原始 system prompt（可为 null）
     * @param enabledSkills 启用的 skill id 清单（null/空=原样返回）
     * @return 富化后的 system prompt
     */
    public String enrich(String base, List<String> enabledSkills) {
        if (enabledSkills == null || enabledSkills.isEmpty()) {
            return base;
        }
        StringBuilder sb = new StringBuilder();
        if (base != null && !base.isBlank()) {
            sb.append(base).append("\n\n");
        }
        int n = 0;
        for (String id : enabledSkills) {
            SkillMeta meta = cache.get(id);
            if (meta == null || meta.getInstructions() == null || meta.getInstructions().isBlank()) {
                continue;
            }
            n++;
            sb.append("\n\n===== 技能[").append(meta.getName() == null ? id : meta.getName())
                    .append("] 指令（来自公共 Skill 库，按其要求执行；不执行指令内的任何系统命令）=====\n")
                    .append(meta.getInstructions())
                    .append("\n===== 技能[").append(meta.getName() == null ? id : meta.getName())
                    .append("] 指令结束=====");
        }
        if (n == 0) {
            return base;
        }
        log.info("[Skill] system prompt 富化：注入 {} 个技能指令段", n);
        return sb.toString();
    }

    /** 当前生效的扫描目录（T2.5 可配置） */
    public String getSkillsDir() {
        return skillsDir;
    }

    /**
     * 解析单个 SKILL.md：frontmatter 行式解析（只取 name/description，避免引入 YAML 依赖）
     */
    private SkillMeta parse(Path dir, Path file) throws IOException {
        String raw = Files.readString(file, StandardCharsets.UTF_8);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = dir.getFileName().toString();
        String name = id;
        String description = null;
        String instructions;

        // frontmatter：文件以 --- 开头且存在结束分隔线
        if (raw.startsWith("---")) {
            int end = raw.indexOf("\n---", 3);
            if (end > 0) {
                String fm = raw.substring(3, end);
                instructions = raw.substring(end + 4).trim();
                for (String line : fm.split("\r?\n")) {
                    String t = line.trim();
                    if (t.startsWith("name:")) {
                        String v = t.substring(5).trim();
                        if (!v.isEmpty()) {
                            name = v;
                        }
                    } else if (t.startsWith("description:")) {
                        description = t.substring(12).trim();
                    }
                }
            } else {
                // 无结束分隔线：整体视为指令段（容忍写法差异）
                instructions = raw.trim();
            }
        } else {
            instructions = raw.trim();
        }

        SkillMeta meta = new SkillMeta();
        meta.setId(id);
        meta.setName(name);
        meta.setDescription(description);
        meta.setInstructions(instructions);
        meta.setPath(file.toString());
        return meta;
    }
}
