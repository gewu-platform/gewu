package com.example.lc4j.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * MyBatis-Plus 配置（对齐 java-ai-native MybatisPlusConfig）
 *
 * 分页插件：P6 监控日志 selectPage 必须，否则 Page 不生效返回全量。
 * 本项目无 MetaObjectHandler（实体时间由表默认值/手动 set 维护），保持一致不添加。
 */
@Configuration
public class MybatisPlusConfig {

    /**
     * 分页插件（PG 方言）
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        interceptor.addInnerInterceptor(new PaginationInnerInterceptor(DbType.POSTGRE_SQL));
        return interceptor;
    }
}
