package com.gewu.module.setting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.client.EmbeddingModelConfig;
import com.gewu.module.setting.config.ProviderSeedConfig.SeedProps;
import com.gewu.client.LangChain4jConfig;
import com.gewu.client.VolcMultiModalEmbeddingModel;
import com.gewu.common.GewuCrypto;
import com.gewu.module.setting.entity.LlmProvider;
import com.gewu.module.setting.mapper.LlmProviderMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型配置中心服务（T3 核心：配置来源从 yml 单源升级为 DB 优先 + yml 兜底）
 *
 * <p>职责边界（关键设计）：</p>
 * <ul>
 *   <li><b>chat 侧</b>：DB 的 chat 配置 <b>merge 进</b> {@link LangChain4jConfig#getModels()}，
 *       激活行覆盖 {@code activeModel}——全部消费方（ConversationService/ChatController/AgentService…）
 *       继续经工厂取模型与校验 {@code getModels().containsKey()}，<b>零改动</b>；
 *       thinking 压制、超时、重试等构建逻辑全部保留在工厂内部。</li>
 *   <li><b>embedding 侧</b>：本服务持有版本化缓存的单例替代品，消费方
 *       （KnowledgeBaseService/KnowledgeIndexingService）改为注入本服务取模型。</li>
 * </ul>
 *
 * <p>生效模型：任何写操作后 {@code version+1} 并清空工厂缓存——chat 模型实例由工厂的
 * computeIfAbsent 池按新配置自然重建；embedding 实例按 {@code configVersion} 失效重建。
 * 「动态生效」= 不重启进程，下一次调用即用新配置。</p>
 */
@Slf4j
@Service
@ConfigurationProperties(prefix = "langchain4j.embedding")
public class ModelProviderService {

    private final LlmProviderMapper providerMapper;
    private final LangChain4jConfig langChain4jConfig;
    private final EmbeddingModelConfig embeddingModelConfig;
    private final SeedProps seedProps;

    /**
     * embedding 配置版本号：任何 embedding 相关写操作 +1，
     * 缓存的 embedding 实例携带创建时版本，不匹配即重建。
     */
    private final AtomicLong embeddingConfigVersion = new AtomicLong(0);

    /** embedding 实例缓存（volatile 保证跨线程可见） */
    private volatile VolcMultiModalEmbeddingModel cachedEmbeddingModel;
    private volatile long cachedAtVersion = -1;

    // ---- EmbeddingModelConfig 同名属性（@ConfigurationProperties 直接绑定 yml embedding 段，作为 DB 兜底源）----
    private String baseUrl;
    private String apiKey;
    private String model;
    private int dimension = 2048;
    private String mode = "multimodal";

    public ModelProviderService(LlmProviderMapper providerMapper,
                                LangChain4jConfig langChain4jConfig,
                                EmbeddingModelConfig embeddingModelConfig,
                                SeedProps seedProps) {
        this.providerMapper = providerMapper;
        this.langChain4jConfig = langChain4jConfig;
        this.embeddingModelConfig = embeddingModelConfig;
        this.seedProps = seedProps;
    }

    // ==================== 生效链路 ====================

    /**
     * 将 DB 配置刷入运行时（应用启动时与每次写操作后调用）。
     * chat：merge 进工厂 models map + 激活行覆盖 activeModel；
     * embedding：bump 版本号使缓存实例失效（惰性重建）。
     */
    public synchronized void refresh() {
        List<LlmProvider> all = providerMapper.selectList(null);

        List<LlmProvider> chats = all.stream()
                .filter(p -> "chat".equals(p.getProviderType()))
                .toList();
        // 重建式合并：yml 原始配置为底座 + DB 行覆盖/追加（DB 已删除的配置不再残留运行时 map）
        langChain4jConfig.getModels().clear();
        langChain4jConfig.getModels().putAll(seedProps.getModels());
        for (LlmProvider p : chats) {
            LangChain4jConfig.ModelConfig mc = toChatModelConfig(p);
            langChain4jConfig.getModels().put(p.getName(), mc);
        }
        // 激活行覆盖 activeModel（未标记激活或 DB 无 chat 配置时保持 yml 原值）
        chats.stream().filter(p -> Boolean.TRUE.equals(p.getIsActive()))
                .findFirst()
                .ifPresentOrElse(
                        p -> langChain4jConfig.setActiveModel(p.getName()),
                        () -> langChain4jConfig.setActiveModel(seedProps.getActiveModel()));

        boolean embeddingChanged = all.stream()
                .anyMatch(p -> "embedding".equals(p.getProviderType()));
        if (embeddingChanged) {
            embeddingConfigVersion.incrementAndGet();
        }
        log.info("[T3] 模型配置已刷新：chat={} 条（merge 进工厂），embedding 配置存在={}（版本 {}）",
                chats.size(), embeddingChanged, embeddingConfigVersion.get());
    }

    /**
     * DB 行 → 工厂 ModelConfig（解密 key；密文损坏时抛出带引导的异常）
     */
    private LangChain4jConfig.ModelConfig toChatModelConfig(LlmProvider p) {
        LangChain4jConfig.ModelConfig mc = new LangChain4jConfig.ModelConfig();
        mc.setProvider(p.getVendor());
        mc.setBaseUrl(p.getBaseUrl());
        mc.setApiKey(decryptKey(p));
        mc.setModelName(p.getModelName());
        mc.setTemperature(p.getTemperature() != null ? p.getTemperature().doubleValue() : 0.7);
        mc.setMaxTokens(p.getMaxTokens() != null ? p.getMaxTokens() : 1024);
        mc.setDescription(p.getDescription());
        return mc;
    }

    // ==================== 消费方取模型入口 ====================

    /**
     * 获取当前生效的 embedding 模型（替代原 EmbeddingModelConfig 的 @Bean 单例注入）。
     * DB 有 embedding 配置 → 用 DB；否则回退 yml（原 @Bean 行为）。
     * 版本化缓存：配置版本与实例创建版本不一致时重建。
     */
    public VolcMultiModalEmbeddingModel getEmbeddingModel() {
        long version = embeddingConfigVersion.get();
        VolcMultiModalEmbeddingModel cached = cachedEmbeddingModel;
        if (cached != null && cachedAtVersion == version) {
            return cached;
        }
        synchronized (this) {
            version = embeddingConfigVersion.get();
            if (cachedEmbeddingModel != null && cachedAtVersion == version) {
                return cachedEmbeddingModel;
            }
            VolcMultiModalEmbeddingModel created = createEmbeddingModel();
            cachedEmbeddingModel = created;
            cachedAtVersion = version;
            log.info("[T3] embedding 模型实例已重建（配置版本 {}）", version);
            return created;
        }
    }

    /**
     * 构建当前生效配置的 embedding 实例
     */
    private VolcMultiModalEmbeddingModel createEmbeddingModel() {
        LlmProvider db = providerMapper.selectOne(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getProviderType, "embedding")
                .orderByDesc(LlmProvider::getIsActive)
                .last("LIMIT 1"));
        if (db == null) {
            // yml 兜底：与原 @Bean 行为完全一致
            return new VolcMultiModalEmbeddingModel(
                    embeddingModelConfig.getBaseUrl(),
                    embeddingModelConfig.getApiKey(),
                    embeddingModelConfig.getModel(),
                    embeddingModelConfig.getDimension(),
                    embeddingModelConfig.getMode());
        }
        return new VolcMultiModalEmbeddingModel(
                db.getBaseUrl(),
                decryptKey(db),
                db.getModelName(),
                db.getDimension() != null ? db.getDimension() : embeddingModelConfig.getDimension(),
                db.getMode() != null ? db.getMode() : embeddingModelConfig.getMode());
    }

    // ==================== 写操作（触发刷新） ====================

    /**
     * 新增/更新后统一调用：version+1 + 清工厂缓存 + 重新 merge。
     * 由 LlmProviderService 的写方法调用；chat 模型实例由工厂池按新配置重建。
     */
    public void onConfigChanged() {
        langChain4jConfig.clearFactoryCaches();
        refresh();
        log.info("[T3] 配置变更已生效：工厂缓存已清空，下一次调用使用新配置");
    }

    // ==================== 密钥处理 ====================

    /**
     * 解密 DB 中的 key；兼容历史明文（isEncrypted=false 时原样返回——不应出现，防御性保留）
     */
    private String decryptKey(LlmProvider p) {
        String stored = p.getApiKeyEnc();
        if (stored == null || stored.isBlank()) {
            throw new IllegalStateException("模型配置 [" + p.getName() + "] 缺少 API Key，请在模型设置页补全");
        }
        return GewuCrypto.isEncrypted(stored) ? GewuCrypto.decrypt(stored) : stored;
    }

    // ---- @ConfigurationProperties 绑定用 setter（yml embedding 段兜底源）----

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public int getDimension() { return dimension; }
    public void setDimension(int dimension) { this.dimension = dimension; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
}
