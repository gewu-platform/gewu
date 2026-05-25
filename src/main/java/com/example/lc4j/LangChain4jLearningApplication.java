package com.example.lc4j;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * LangChain4j Learning - Spring Boot 启动类
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
public class LangChain4jLearningApplication {

    public static void main(String[] args) {
        SpringApplication.run(LangChain4jLearningApplication.class, args);
        System.out.println("""
            ╔══════════════════════════════════════════════════╗
            ║   LangChain4j Learning - 学习项目已启动          ║
            ║   Day 1: 同步/流式调用                           ║
            ╚══════════════════════════════════════════════════╝
            """);
    }
}
