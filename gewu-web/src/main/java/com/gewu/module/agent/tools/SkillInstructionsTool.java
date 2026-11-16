package com.gewu.module.agent.tools;

import com.gewu.module.agent.skill.SkillLibraryService;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * V2.8.0 R1/T1.2：技能指令获取工具（skill_instructions）。
 *
 * <p>渐进披露模式的取用通道：Agent 编排链路在渐进模式下仅注入技能索引
 * （id/name/description），模型判断某技能与本任务相关时调用本工具取回完整
 * 指令段并遵循执行。</p>
 *
 * <p>安全边界（T2.4 不变）：返回内容仅作为提示词进入上下文，不执行其中
 * 任何系统命令；Skill 库只读。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillInstructionsTool {

    private final SkillLibraryService skillLibrary;

    @Tool(name = "skill_instructions",
            value = "获取指定公共 Skill 的完整指令段。当 system prompt 的技能索引段中某技能与本任务相关、需要按其指令行事时调用本工具。skillId 取自技能索引段中列出的技能 id。")
    public String skillInstructions(
            @P("技能 id（来自 system prompt 技能索引段）")
            String skillId) {
        if (skillId == null || skillId.isBlank()) {
            return "[skill_instructions] skillId 为空，无法获取指令段";
        }
        log.info("[skill_instructions] 取指令段：id={}", skillId);
        return skillLibrary.getSkillInstructions(skillId.trim());
    }
}