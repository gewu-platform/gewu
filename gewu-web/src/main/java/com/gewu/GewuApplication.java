package com.gewu;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Gewu - Spring Boot 启动类
 * 
 * Day 1: 快速上手 - 同步/流式调用
 * Day 2: Prompt Template + Chat Memory
 * Day 3: AI Services（接口驱动）
 * Day 4: Tool Calling / Function Calling
 * Day 5: Output Parsing + 综合练习
 * Day 6: Spring AI vs LangChain4j 深度对比
 * Day 7: 复习 + 准备第 4 周
 */
@SpringBootApplication
public class GewuApplication {

    public static void main(String[] args) {
        SpringApplication.run(GewuApplication.class, args);
        System.out.println("""
            ╔═════════════════════════════════════════════════╗
            ║   格物 Gewu - 服务已启动
            ╚═════════════════════════════════════════════════╝
            """);
    }
}
