package com.gewu.module.prompt.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import com.gewu.common.ErrorCode;
import com.gewu.module.prompt.dto.PromptExecuteResponse;
import com.gewu.module.prompt.entity.PromptTemplate;
import com.gewu.module.prompt.mapper.PromptTemplateMapper;
import dev.langchain4j.model.chat.ChatModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prompt 模板 Service（对齐 java-ai-native PromptTemplateService）
 *
 * 版本制模板：创建自动 +1 版本；回滚 = 目标版本启用、其余停用；
 * 执行 = fillTemplate 填充 {variable} → ChatModel 调用（带/不带 systemPrompt）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateMapper promptTemplateMapper;
    private final LangChain4jConfig langChain4jConfig;

    /**
     * 获取启用的模板（优先最新版本）
     */
    public PromptTemplate getActiveTemplate(String name) {
        PromptTemplate template = promptTemplateMapper.selectLatestVersion(name);
        if (template == null || !Boolean.TRUE.equals(template.getIsActive())) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(), "模板不存在或未启用：" + name);
        }
        return template;
    }

    /**
     * 执行模板：填充变量 → 调用 AI → 返回结果
     */
    public PromptExecuteResponse executeTemplate(String templateName, Map<String, String> variables) {
        PromptTemplate template = getActiveTemplate(templateName);
        long startTime = System.currentTimeMillis();

        // 填充模板变量
        String userPrompt = fillTemplate(template.getUserPromptTemplate(), variables);

        // 解析模型：模板指定 > 当前激活；未知模型直接报参数错误
        String modelId = (template.getModel() != null && !template.getModel().isBlank())
                ? template.getModel()
                : langChain4jConfig.getActiveModel();
        if (!langChain4jConfig.getModels().containsKey(modelId)) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "未知模型 ID: " + modelId);
        }

        ChatModel chatModel = template.getMaxTokens() != null
                ? langChain4jConfig.getChatModel(modelId, template.getMaxTokens())
                : langChain4jConfig.getChatModel(modelId);

        String content;
        try {
            List<dev.langchain4j.data.message.ChatMessage> messages = new java.util.ArrayList<>();
            if (template.getSystemPrompt() != null && !template.getSystemPrompt().isBlank()) {
                messages.add(dev.langchain4j.data.message.SystemMessage.from(template.getSystemPrompt()));
            }
            messages.add(dev.langchain4j.data.message.UserMessage.from(userPrompt));
            content = chatModel.chat(messages).aiMessage().text();
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("执行 Prompt 模板失败：templateName={}, error={}", templateName, e.getMessage(), e);
            throw new BizException(ErrorCode.API_CALL_FAILED.getCode(), "模板执行失败：" + e.getMessage());
        }

        long duration = System.currentTimeMillis() - startTime;

        // 估算 token（简化计算，对齐 native）
        int inputTokens = (userPrompt.length() +
                (template.getSystemPrompt() != null ? template.getSystemPrompt().length() : 0)) / 4;
        int outputTokens = content != null ? content.length() / 4 : 0;

        PromptExecuteResponse response = new PromptExecuteResponse();
        response.setContent(content);
        response.setTemplateName(templateName);
        response.setModel(modelId);
        response.setTokens(inputTokens + outputTokens);
        response.setDuration(duration);

        log.info("Prompt 模板执行成功：templateName={}, model={}, tokens={}, duration={}ms",
                templateName, modelId, response.getTokens(), duration);
        return response;
    }

    /**
     * 创建模板（version 自动 +1）
     */
    @Transactional
    public PromptTemplate createTemplate(PromptTemplate template) {
        if (template.getName() == null || template.getName().isBlank()
                || template.getUserPromptTemplate() == null || template.getUserPromptTemplate().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST.getCode(), "模板名称和 User Prompt 模板不能为空");
        }

        // 查询当前最大版本号
        List<PromptTemplate> existing = promptTemplateMapper.selectVersionsByName(template.getName());
        int nextVersion = existing.isEmpty() ? 1 : existing.get(0).getVersion() + 1;
        template.setVersion(nextVersion);
        template.setId(null);

        if (template.getIsActive() == null) {
            template.setIsActive(true);
        }
        if (template.getTemperature() == null) {
            template.setTemperature(0.7);
        }
        if (template.getMaxTokens() == null) {
            template.setMaxTokens(2048);
        }
        if (template.getIsDeleted() == null) {
            template.setIsDeleted(0);
        }

        // 本项目无 MetaObjectHandler，时间手动填充
        LocalDateTime now = LocalDateTime.now();
        template.setCreateTime(now);
        template.setUpdateTime(now);

        promptTemplateMapper.insert(template);
        log.info("创建 Prompt 模板：name={}, version={}", template.getName(), nextVersion);
        return template;
    }

    /**
     * 列出所有模板（未删除，按创建时间倒序）
     */
    public List<PromptTemplate> listTemplates() {
        LambdaQueryWrapper<PromptTemplate> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(PromptTemplate::getIsDeleted, 0)
                .orderByDesc(PromptTemplate::getCreateTime);
        return promptTemplateMapper.selectList(wrapper);
    }

    /**
     * 获取模板详情（最新版本）
     */
    public PromptTemplate getTemplate(String name) {
        PromptTemplate template = promptTemplateMapper.selectLatestVersion(name);
        if (template == null) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(), "模板不存在：" + name);
        }
        return template;
    }

    /**
     * 回滚到指定版本（目标版本启用，其余停用）
     */
    @Transactional
    public PromptTemplate rollbackTemplate(String name, int targetVersion) {
        // 检查目标版本是否存在
        PromptTemplate target = promptTemplateMapper.selectByVersion(name, targetVersion);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND.getCode(),
                    "模板版本不存在：" + name + " v" + targetVersion);
        }

        // 将当前最新版本设为不启用
        PromptTemplate latest = promptTemplateMapper.selectLatestVersion(name);
        if (latest != null && !latest.getVersion().equals(targetVersion)) {
            latest.setIsActive(false);
            latest.setUpdateTime(LocalDateTime.now());
            promptTemplateMapper.updateById(latest);
        }

        // 启用目标版本
        target.setIsActive(true);
        target.setUpdateTime(LocalDateTime.now());
        promptTemplateMapper.updateById(target);

        log.info("回滚 Prompt 模板：name={}, targetVersion={}", name, targetVersion);
        return target;
    }

    /**
     * 替换 {variable} 占位符
     */
    public String fillTemplate(String template, Map<String, String> variables) {
        if (variables == null || variables.isEmpty()) {
            return template;
        }

        String result = template;
        for (Map.Entry<String, String> entry : variables.entrySet()) {
            String placeholder = "{" + entry.getKey() + "}";
            String value = entry.getValue() != null ? entry.getValue() : "";
            result = result.replace(placeholder, value);
        }

        // 检查是否还有未替换的占位符
        Pattern pattern = Pattern.compile("\\{\\w+\\}");
        Matcher matcher = pattern.matcher(result);
        if (matcher.find()) {
            log.warn("模板中存在未替换的占位符：{}", matcher.group());
        }

        return result;
    }
}
