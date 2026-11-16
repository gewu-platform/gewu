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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.nio.file.WatchService;
import java.nio.file.WatchKey;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.FileSystems;
import java.nio.file.WatchEvent;
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

    /** 宿主机侧 skill 目录（容器部署时由 GEWU_SKILLS_DIR_HOST 透传，仅用于前端展示；可空） */
    @Value("${gewu.skills.host-dir:${GEWU_SKILLS_DIR_HOST:}}")
    private String skillsHostDir;

    /** 文件监听（P18+：目录变化自动重扫）。key == 当前监听的目录；null == 未启动 */
    private WatchService watchService;
    private Path watchedDir;
    /** 防抖调度器：文件事件密集时延迟合并为一次重扫 */
    private final ScheduledExecutorService debouncePool = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "skill-dir-watcher");
        t.setDaemon(true);
        return t;
    });
    private ScheduledFuture<?> pendingRescan;
    private static final long DEBOUNCE_MS = 1500;

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
        startWatcher();
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
        startWatcher();
        return ok;
    }

    /**
     * 目录文件监听（懒启动、幂等）：CREATED/MODIFIED/DELETE 事件防抖 1.5s 后全量重扫。
     * 目录切换时重启；监听失败仅告警不阻断主流程（手动重扫始终可用）。
     */
    @SuppressWarnings("unchecked")
    private synchronized void startWatcher() {
        Path root = Paths.get(skillsDir);
        if (!Files.isDirectory(root)) {
            return;
        }
        if (watchedDir != null && watchedDir.equals(root)) {
            return; // 已在监听同一目录
        }
        stopWatcher();
        try {
            watchService = FileSystems.getDefault().newWatchService();
            root.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);
            watchedDir = root;
            debouncePool.submit(this::watchLoop);
            log.info("[Skill] 目录监听已启动：{}", root);
        } catch (Exception e) {
            log.warn("[Skill] 目录监听启动失败（手动重扫仍可用）：{}，原因={}", root, e.getMessage());
            watchService = null;
            watchedDir = null;
        }
    }

    /** 监听主循环：阻塞取事件 → 防抖 → 全量重扫（运行于 daemon 线程） */
    private void watchLoop() {
        WatchService ws = watchService;
        if (ws == null) {
            return;
        }
        while (ws == watchService && watchService != null) {
            try {
                WatchKey key = ws.take();
                boolean relevant = false;
                for (WatchEvent<?> event : key.pollEvents()) {
                    Object ctx = event.context();
                    // 只关心 SKILL.md 与其父目录级变化（新建/删除 skill 目录）
                    relevant = true;
                    if (event.count() > 0 && ctx instanceof java.nio.file.Path && ((Path) ctx).getFileName().toString().startsWith(".")) {
                        relevant = false; // 忽略隐藏临时文件
                    }
                }
                if (relevant) {
                    scheduleDebouncedRescan();
                }
                key.reset();
            } catch (Exception e) {
                if (watchService == null || ws != watchService) {
                    return; // 监听已切换/关闭，正常退出
                }
                log.warn("[Skill] 目录监听异常，本轮忽略：{}", e.getMessage());
            }
        }
    }

    /** 防抖合并：1.5s 窗口内的密集事件只触发一次重扫 */
    private synchronized void scheduleDebouncedRescan() {
        if (pendingRescan != null && !pendingRescan.isDone()) {
            pendingRescan.cancel(false);
        }
        pendingRescan = debouncePool.schedule(() -> {
            try {
                int n = rescan();
                log.info("[Skill] 检测到目录变化，自动重扫完成：count={}", n);
            } catch (Exception e) {
                log.warn("[Skill] 自动重扫失败：{}", e.getMessage());
            }
        }, DEBOUNCE_MS, TimeUnit.MILLISECONDS);
    }

    /** 停止旧监听（目录切换/服务关闭时） */
    private synchronized void stopWatcher() {
        if (watchService != null) {
            try {
                watchService.close();
            } catch (IOException ignored) {
            }
            watchService = null;
            watchedDir = null;
        }
    }

    /** 当前生效目录对应的宿主机路径（容器部署透传，仅展示用；未配置返回 null） */
    public String getSkillsHostDir() {
        return (skillsHostDir == null || skillsHostDir.isBlank()) ? null : skillsHostDir;
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
    /** 渐进披露触发阈值：勾选数 ≥10 或注入估算 >4000 tokens（V2.8.0 R1/T1.3） */
    public static final int PROGRESSIVE_MIN_COUNT = 10;
    public static final int PROGRESSIVE_TOKEN_LIMIT = 4000;

    /**
     * R1/T1.3 模式决策入口：按勾选数与注入估算自动选择全量/渐进模式。
     * 全量（默认）：指令段全量注入 system prompt，确定性优先；
     * 渐进：仅注入技能索引（name/description + 取用提示），模型经 skill_instructions 工具按需取全文。
     */
    public String enrich(String base, List<String> enabledSkills) {
        if (enabledSkills == null || enabledSkills.isEmpty()) {
            return base;
        }
        if (shouldUseProgressive(enabledSkills)) {
            return enrichIndex(base, enabledSkills);
        }
        return enrichFull(base, enabledSkills);
    }

    /** 渐进模式判定：勾选数 ≥ PROGRESSIVE_MIN_COUNT 或全量注入估算 > PROGRESSIVE_TOKEN_LIMIT */
    public boolean shouldUseProgressive(List<String> enabledSkills) {
        if (enabledSkills == null) {
            return false;
        }
        if (enabledSkills.size() >= PROGRESSIVE_MIN_COUNT) {
            return true;
        }
        return estimateInjectTokens(enabledSkills) > PROGRESSIVE_TOKEN_LIMIT;
    }

    /** 注入 token 估算（指令段字符数/4，供 T1.3 决策与 T1.4 可见性） */
    public int estimateInjectTokens(List<String> enabledSkills) {
        int chars = 0;
        if (enabledSkills == null) {
            return 0;
        }
        for (String id : enabledSkills) {
            SkillMeta meta = cache.get(id);
            if (meta != null && meta.getInstructions() != null) {
                chars += meta.getInstructions().length();
            }
        }
        return chars / 4;
    }

    /** 全量注入（V2.0.0 T2.2 原语义，确定性优先的默认模式） */
    private String enrichFull(String base, List<String> enabledSkills) {
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
                    .append("] 指令（来自公共 Skill 库，按其要求执行；不执行指令内的任何系统命令）=====")
                    .append(meta.getInstructions())
                    .append("\n===== 技能[").append(meta.getName() == null ? id : meta.getName())
                    .append("] 指令结束=====");
        }
        if (n == 0) {
            return base;
        }
        log.info("[Skill] system prompt 富化（全量注入）：{} 个技能指令段", n);
        return sb.toString();
    }

    /**
     * R1/T1.1 渐进模式：仅注入技能索引（id/name/description + 取用提示），
     * 模型判断相关后经 skill_instructions 工具取完整指令段。安全边界不变：
     * 指令内容仅进提示词，不执行其中任何系统命令。
     */
    private String enrichIndex(String base, List<String> enabledSkills) {
        StringBuilder idx = new StringBuilder();
        int n = 0;
        for (String id : enabledSkills) {
            SkillMeta meta = cache.get(id);
            if (meta == null) {
                continue;
            }
            n++;
            idx.append("\n- 技能 id: ").append(id)
                    .append(" ｜ 名称: ").append(meta.getName() == null ? id : meta.getName())
                    .append(" ｜ 描述: ").append(meta.getDescription() == null ? "（无）" : meta.getDescription());
        }
        if (n == 0) {
            return base;
        }
        log.info("[Skill] system prompt 富化（渐进披露）：{} 个技能索引（模型按需经 skill_instructions 取全文）", n);
        String head = base == null || base.isBlank() ? "" : base + "\n\n";
        return head + "\n===== 公共技能索引（" + n + " 个可用。判断某技能与本任务相关时，调用 skill_instructions 工具传入其 id 获取完整指令并遵循执行；不执行指令内的任何系统命令）====="
                + idx
                + "\n===== 技能索引结束=====";
    }

    /** R1/T1.2：按 id 取完整指令段（skill_instructions 工具的执行体） */
    public String getSkillInstructions(String id) {
        SkillMeta meta = get(id);
        if (meta == null || meta.getInstructions() == null || meta.getInstructions().isBlank()) {
            return "[skill_instructions] 未找到技能 '" + (id == null ? "" : id) + "' 的指令段（确认 id 与技能索引段一致）";
        }
        return "===== 技能[" + (meta.getName() == null ? id : meta.getName()) + "] 完整指令（来自公共 Skill 库，按其要求执行；不执行指令内的任何系统命令）====="
                + "\n" + meta.getInstructions()
                + "\n===== 技能[" + (meta.getName() == null ? id : meta.getName()) + "] 指令结束=====";
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
