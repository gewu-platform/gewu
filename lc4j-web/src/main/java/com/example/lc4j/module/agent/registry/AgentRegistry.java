package com.example.lc4j.module.agent.registry;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.example.lc4j.module.agent.definition.AgentConfig;
import com.example.lc4j.module.agent.definition.AgentDefinition;
import com.example.lc4j.module.agent.entity.AgentDefinitionEntity;
import com.example.lc4j.module.agent.mapper.AgentDefinitionMapper;
import com.example.lc4j.module.agent.tools.ToolRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 注册中心（对齐 java-ai-native AgentRegistry）
 *
 * 内存缓存 + 数据库持久化；启动时加载 enabled=true 的定义。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRegistry {

    private final AgentDefinitionMapper agentDefinitionMapper;
    private final ToolRegistry toolRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Agent 内存缓存（仅启用项） */
    private final Map<String, AgentDefinition> agentCache = new ConcurrentHashMap<>();

    /**
     * 启动时加载所有启用的 Agent 到缓存
     */
    @PostConstruct
    public void init() {
        log.info("初始化 Agent 注册中心...");
        agentCache.clear();

        List<AgentDefinitionEntity> entities = agentDefinitionMapper.selectList(
                new LambdaQueryWrapper<AgentDefinitionEntity>()
                        .eq(AgentDefinitionEntity::getEnabled, 1));

        for (AgentDefinitionEntity entity : entities) {
            AgentDefinition definition = toDefinition(entity);
            agentCache.put(definition.getId(), definition);
        }

        log.info("已加载 {} 个 Agent 到缓存", agentCache.size());
    }

    /**
     * 注册 Agent（入库 + 缓存）
     */
    public AgentDefinition register(AgentDefinition definition) {
        AgentDefinitionEntity entity = toEntity(definition);
        if (entity.getCreateTime() == null) {
            entity.setCreateTime(LocalDateTime.now());
        }
        if (entity.getUpdateTime() == null) {
            entity.setUpdateTime(LocalDateTime.now());
        }
        agentDefinitionMapper.insert(entity);
        agentCache.put(definition.getId(), definition);
        log.info("注册 Agent: {} ({})", definition.getName(), definition.getId());
        return definition;
    }

    /**
     * 更新 Agent
     */
    public AgentDefinition update(AgentDefinition definition) {
        AgentDefinitionEntity entity = toEntity(definition);
        entity.setUpdateTime(LocalDateTime.now());
        agentDefinitionMapper.updateById(entity);
        agentCache.put(definition.getId(), definition);
        log.info("更新 Agent: {} ({})", definition.getName(), definition.getId());
        return definition;
    }

    /**
     * 启用/禁用（只更新 enabled 字段，避免整行 JSON 字段回写问题）
     */
    public void setEnabled(String id, boolean enabled) {
        LambdaUpdateWrapper<AgentDefinitionEntity> wrapper = new LambdaUpdateWrapper<AgentDefinitionEntity>()
                .eq(AgentDefinitionEntity::getId, id)
                .set(AgentDefinitionEntity::getEnabled, enabled ? 1 : 0)
                .set(AgentDefinitionEntity::getUpdateTime, LocalDateTime.now());
        agentDefinitionMapper.update(null, wrapper);

        if (enabled) {
            AgentDefinitionEntity refreshed = agentDefinitionMapper.selectById(id);
            if (refreshed != null) {
                agentCache.put(id, toDefinition(refreshed));
            }
        } else {
            agentCache.remove(id);
        }
        log.info("设置 Agent {} enabled={}", id, enabled);
    }

    /**
     * 删除 Agent
     */
    public void delete(String id) {
        agentDefinitionMapper.deleteById(id);
        agentCache.remove(id);
        log.info("删除 Agent: {}", id);
    }

    /**
     * 获取 Agent（缓存 → DB）
     */
    public AgentDefinition get(String id) {
        AgentDefinition definition = agentCache.get(id);
        if (definition != null) {
            return definition;
        }
        AgentDefinitionEntity entity = agentDefinitionMapper.selectById(id);
        if (entity != null) {
            definition = toDefinition(entity);
            if (Integer.valueOf(1).equals(entity.getEnabled())) {
                agentCache.put(id, definition);
            }
            return definition;
        }
        return null;
    }

    /**
     * 所有 Agent（含禁用，管理页用）
     */
    public List<AgentDefinition> listAll() {
        List<AgentDefinitionEntity> entities = agentDefinitionMapper.selectList(
                new LambdaQueryWrapper<AgentDefinitionEntity>().orderByAsc(AgentDefinitionEntity::getCreateTime));
        List<AgentDefinition> result = new ArrayList<>();
        for (AgentDefinitionEntity e : entities) {
            result.add(toDefinition(e));
        }
        return result;
    }

    /**
     * 已启用 Agent（缓存）
     */
    public List<AgentDefinition> listEnabled() {
        return new ArrayList<>(agentCache.values());
    }

    /**
     * Agent 绑定的工具规格
     */
    public List<dev.langchain4j.agent.tool.ToolSpecification> getToolSpecifications(String agentId) {
        AgentDefinition definition = get(agentId);
        if (definition == null) {
            return Collections.emptyList();
        }
        return toolRegistry.getSpecifications(definition.getToolIds());
    }

    /**
     * 重新加载缓存
     */
    public void reload() {
        init();
    }

    // ========== 转换方法 ==========

    private AgentDefinition toDefinition(AgentDefinitionEntity entity) {
        return AgentDefinition.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .avatar(entity.getAvatar())
                .version(entity.getVersion())
                .type(entity.getType())
                .systemPrompt(entity.getSystemPrompt())
                .greeting(entity.getGreeting())
                .toolIds(parseToolIds(entity.getToolIds()))
                .config(parseConfig(entity.getConfig()))
                .enabled(Integer.valueOf(1).equals(entity.getEnabled()))
                .creatorId(entity.getCreatorId())
                .createdAt(toEpoch(entity.getCreateTime()))
                .updatedAt(toEpoch(entity.getUpdateTime()))
                .build();
    }

    private AgentDefinitionEntity toEntity(AgentDefinition definition) {
        AgentDefinitionEntity entity = new AgentDefinitionEntity();
        entity.setId(definition.getId());
        entity.setName(definition.getName());
        entity.setDescription(definition.getDescription());
        entity.setAvatar(definition.getAvatar());
        entity.setVersion(definition.getVersion() != null ? definition.getVersion() : "1.0.0");
        entity.setType(definition.getType() != null ? definition.getType() : "react");
        entity.setSystemPrompt(definition.getSystemPrompt());
        entity.setGreeting(definition.getGreeting());
        entity.setToolIds(toJson(definition.getToolIds()));
        entity.setConfig(toJson(definition.getConfig() != null ? definition.getConfig() : AgentConfig.builder().build()));
        entity.setEnabled(Boolean.TRUE.equals(definition.getEnabled()) ? 1 : 0);
        entity.setCreatorId(definition.getCreatorId());
        return entity;
    }

    private List<String> parseToolIds(String json) {
        if (json == null || json.isEmpty()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("解析 toolIds 失败: {}", json);
            return Collections.emptyList();
        }
    }

    private AgentConfig parseConfig(String json) {
        if (json == null || json.isEmpty()) {
            return AgentConfig.builder().build();
        }
        try {
            return objectMapper.readValue(json, AgentConfig.class);
        } catch (Exception e) {
            log.warn("解析 config 失败: {}", json);
            return AgentConfig.builder().build();
        }
    }

    private String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            log.warn("序列化失败", e);
            return null;
        }
    }

    private Long toEpoch(LocalDateTime t) {
        return t != null ? t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() : null;
    }
}
