package com.gewu.module.plugin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.plugin.entity.PluginRegistry;
import org.apache.ibatis.annotations.Mapper;

/**
 * V2.0.0 R3/T3.2：插件仓库 Mapper
 *
 * <p>本项目不使用 MapperScan，各 Mapper 以 @Mapper 注解注册（对齐 McpServerMapper）。</p>
 */
@Mapper
public interface PluginRegistryMapper extends BaseMapper<PluginRegistry> {
}
