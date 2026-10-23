package com.gewu.module.chat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.chat.entity.SystemPromptConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统提示词配置 Mapper（对齐 java-ai-native SystemPromptConfigMapper）
 */
@Mapper
public interface SystemPromptConfigMapper extends BaseMapper<SystemPromptConfig> {
}
