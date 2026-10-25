package com.gewu.module.setting.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.setting.entity.LlmProvider;
import org.apache.ibatis.annotations.Mapper;

/**
 * 模型服务配置 Mapper（T3 模型配置中心，对齐既有 BaseMapper 风格）
 */
@Mapper
public interface LlmProviderMapper extends BaseMapper<LlmProvider> {
}
