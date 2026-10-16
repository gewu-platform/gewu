package com.example.lc4j.example;

import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Day 1: LangChain4j 流式调用
 *
 * 运行方式：设置 langchain4j.day1.streaming=true
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "langchain4j.day1.streaming", havingValue = "true", matchIfMissing = false)
public class Day1StreamingExample implements CommandLineRunner {

    @Value("${langchain4j.open-ai.chat-model.base-url}")
    private String baseUrl;

    @Value("${langchain4j.open-ai.chat-model.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.chat-model.model-name}")
    private String modelName;

    @Override
    public void run(String... args) throws Exception {
        log.info("========== Day 1: LangChain4j 流式调用 ==========");

        // LangChain4j 1.x: 流式模型使用 OpenAiStreamingChatModel
        StreamingChatModel streamingModel = OpenAiStreamingChatModel.builder()
                .baseUrl(baseUrl)
                .apiKey(apiKey)
                .modelName(modelName)
                .temperature(0.7)
                .build();

        log.info("流式模型创建成功: modelName={}", modelName);

        log.info("--- 示例: 流式输出 ---");
        log.info("AI 回复: ");

        CompletableFuture<String> future = new CompletableFuture<>();
        StringBuilder fullResponse = new StringBuilder();

        ChatRequest chatRequest = ChatRequest.builder()
                .messages(UserMessage.from("请写一首关于 Java 的短诗（5 行以内）"))
                .build();

        streamingModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                System.out.print(partialResponse);
                fullResponse.append(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                System.out.println();
                log.info("流式响应完成，总长度: {} 字符", fullResponse.length());
                future.complete(fullResponse.toString());
            }

            @Override
            public void onError(Throwable error) {
                log.error("流式响应出错", error);
                future.completeExceptionally(error);
            }
        });

        String fullText = future.get(30, TimeUnit.SECONDS);

        log.info("\n========== API 对比 ==========");
        log.info("""
            LangChain4j 1.x:
              StreamingChatModel model = OpenAiStreamingChatModel.builder()...build();
              model.chat(chatRequest, new StreamingChatResponseHandler() {
                  public void onPartialResponse(String token) { ... }
                  public void onCompleteResponse(ChatResponse response) { ... }
                  public void onError(Throwable error) { ... }
              });

            Spring AI:
              Flux<String> output = chatClient.prompt()
                  .user("写首诗")
                  .stream()
                  .content();
              output.subscribe(token -> System.out.print(token));

            差异: LangChain4j 用回调模式，Spring AI 用 Reactor Flux
            """);

        log.info("========== Day 1 流式调用完成 ==========");
    }
}
