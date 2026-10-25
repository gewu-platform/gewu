package com.gewu.module.setting.config;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.client.EmbeddingModelConfig;
import com.gewu.client.LangChain4jConfig;
import com.gewu.common.GewuCrypto;
import com.gewu.module.setting.entity.LlmProvider;
import com.gewu.module.setting.mapper.LlmProviderMapper;
import com.gewu.module.setting.service.ModelProviderService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * T3 首次启动种子：yml → llm_provider 导入
 *
 * <p>触发条件（全部满足才导入）：llm_provider 表为空 且 yml 配置完整。
 * 导入行为：chat 的 models 全量导入（activeModel 行标记 is_active），
 * embedding 段导入一条（默认激活）。key 加密落库（GEWU_ENCRYPT_KEY）。</p>
 *
 * <p>导入后 yml 中的明文默认 key 即可安全移除（开源前必做，见 T3 执行清单）。</p>
 */
@Slf4j
@Configuration
public class ProviderSeedConfig {

    /**
     * yml chat 模型段绑定（与 LangChain4jConfig 同前缀，只读用）
     */
    @Bean
    @ConfigurationProperties(prefix = "langchain4j")
    public SeedProps seedProps() {
        return new SeedProps();
    }

    @Bean
    public ApplicationRunner llmProviderSeeder(LlmProviderMapper mapper,
                                               SeedProps seedProps,
                                               EmbeddingModelConfig embeddingConfig,
                                               ModelProviderService modelProviderService) {
        return args -> {
            Long count = mapper.selectCount(null);
            if (count > 0) {
                log.info("[T3] llm_provider 已有 {} 条配置，跳过种子导入", count);
                return;
            }
            int imported = 0;

            // chat 模型全量导入
            String active = seedProps.getActiveModel();
            if (seedProps.getModels() != null) {
                for (var entry : seedProps.getModels().entrySet()) {
                    LangChain4jConfig.ModelConfig mc = entry.getValue();
                    if (mc == null || !StringUtils.hasText(mc.getApiKey())) {
                        log.warn("[T3] 种子跳过 chat 配置 [{}]（yml 缺 key）", entry.getKey());
                        continue;
                    }
                    LlmProvider p = new LlmProvider();
                    p.setName(entry.getKey());
                    p.setProviderType("chat");
                    p.setVendor(mc.getProvider() != null ? mc.getProvider() : "volcengine");
                    p.setBaseUrl(mc.getBaseUrl());
                    p.setApiKeyEnc(GewuCrypto.encrypt(mc.getApiKey()));
                    p.setModelName(mc.getModelName());
                    p.setTemperature(mc.getTemperature() != null
                            ? java.math.BigDecimal.valueOf(mc.getTemperature()) : java.math.BigDecimal.valueOf(0.7));
                    p.setMaxTokens(mc.getMaxTokens());
                    p.setDescription(mc.getDescription() != null ? mc.getDescription() : "yml 导入（首次启动种子）");
                    p.setIsActive(entry.getKey().equals(active));
                    mapper.insert(p);
                    imported++;
                }
            }

            // embedding 段导入一条
            if (StringUtils.hasText(embeddingConfig.getApiKey())) {
                LlmProvider e = new LlmProvider();
                e.setName("embedding-default");
                e.setProviderType("embedding");
                e.setVendor("volcengine");
                e.setBaseUrl(embeddingConfig.getBaseUrl());
                e.setApiKeyEnc(GewuCrypto.encrypt(embeddingConfig.getApiKey()));
                e.setModelName(embeddingConfig.getModel());
                e.setDimension(embeddingConfig.getDimension());
                e.setMode(embeddingConfig.getMode());
                e.setDescription("yml 导入（首次启动种子）");
                e.setIsActive(true);
                mapper.insert(e);
                imported++;
            }

            if (imported > 0) {
                modelProviderService.refresh();
                log.info("[T3] 首次启动种子完成：yml → llm_provider 导入 {} 条（key 已加密落库，yml 明文默认值现在可安全移除）",
                        imported);
            } else {
                log.info("[T3] llm_provider 为空且 yml 无可用配置，跳过种子（等网页配置）");
            }
        };
    }

    /**
     * 种子读取 yml chat 段的结构镜像（activeModel + models）
     */
    @org.springframework.context.annotation.DependsOn("seedProps")
    public static class SeedProps {
        private String activeModel;
        private java.util.Map<String, LangChain4jConfig.ModelConfig> models = new java.util.HashMap<>();

        public String getActiveModel() { return activeModel; }
        public void setActiveModel(String activeModel) { this.activeModel = activeModel; }
        public java.util.Map<String, LangChain4jConfig.ModelConfig> getModels() { return models; }
        public void setModels(java.util.Map<String, LangChain4jConfig.ModelConfig> models) { this.models = models; }
    }
}
