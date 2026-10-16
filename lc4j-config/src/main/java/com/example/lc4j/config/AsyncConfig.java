package com.example.lc4j.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 异步任务线程池（对齐 java-ai-native AsyncConfig）
 *
 * knowledgeExecutor 用于知识库文档解析/embedding/落库的后台处理，
 * 同时用于 LLM 调用日志的异步记录（P6 接入）。
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("knowledgeExecutor")
    public Executor knowledgeExecutor() {
        ThreadPoolTaskExecutor exec = new ThreadPoolTaskExecutor();
        exec.setCorePoolSize(2);
        exec.setMaxPoolSize(4);
        exec.setQueueCapacity(50);
        exec.setThreadNamePrefix("kb-async-");
        exec.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        exec.initialize();
        return exec;
    }
}
