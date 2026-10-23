package com.gewu.module.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.agent.entity.AgentDefinitionEntity;
import org.apache.ibatis.annotations.Mapper;

/**
 * Agent 定义 Mapper（对齐 java-ai-native AgentDefinitionMapper）
 */
@Mapper
public interface AgentDefinitionMapper extends BaseMapper<AgentDefinitionEntity> {
}
