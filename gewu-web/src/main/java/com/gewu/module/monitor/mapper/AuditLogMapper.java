package com.gewu.module.monitor.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.gewu.module.monitor.entity.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * V2.4.0 C2：审计日志 Mapper
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
