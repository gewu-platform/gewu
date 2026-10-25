package com.gewu.module.setting.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.gewu.common.GewuCrypto;
import com.gewu.module.setting.dto.LlmProviderSaveRequest;
import com.gewu.module.setting.dto.LlmProviderView;
import com.gewu.module.setting.entity.LlmProvider;
import com.gewu.module.setting.mapper.LlmProviderMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 模型配置 CRUD 服务（T3）
 *
 * <p>所有写操作事务内落库、事务后触发 {@link ModelProviderService#onConfigChanged()} 动态生效。
 * 密钥只进不出：保存时 AES-256-GCM 加密落库，查询仅返回掩码。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmProviderService {

    private final LlmProviderMapper providerMapper;
    private final ModelProviderService modelProviderService;

    /**
     * 全量列表（前端按 providerType 分组渲染）
     */
    public List<LlmProviderView> listAll() {
        return providerMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                        .orderByAsc(LlmProvider::getProviderType)
                        .orderByDesc(LlmProvider::getIsActive)
                        .orderByAsc(LlmProvider::getId))
                .stream().map(this::toView).toList();
    }

    /**
     * 新增配置
     */
    @Transactional
    public LlmProviderView create(LlmProviderSaveRequest req) {
        validate(req, null);
        // 名称唯一校验（DB 也有 uk，提前给出友好错误）
        Long dup = providerMapper.selectCount(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getName, req.getName()));
        if (dup > 0) {
            throw new IllegalArgumentException("配置名称已存在: " + req.getName());
        }

        LlmProvider entity = new LlmProvider();
        copyFromRequest(req, entity);
        // 新增必须提供 key
        if (!StringUtils.hasText(req.getApiSpecialKey())) {
            throw new IllegalArgumentException("新增配置必须提供 API Key");
        }
        entity.setApiKeyEnc(GewuCrypto.encrypt(req.getApiSpecialKey().trim()));
        entity.setIsActive(Boolean.TRUE.equals(req.getIsActive()));

        providerMapper.insert(entity);
        deactivateOthersIfNeeded(entity);
        modelProviderService.onConfigChanged();
        log.info("[T3] 新增模型配置: type={}, name={}", entity.getProviderType(), entity.getName());
        return toView(entity);
    }

    /**
     * 更新配置（apiSpecialKey 为空 = 保持原 key）
     */
    @Transactional
    public LlmProviderView update(Long id, LlmProviderSaveRequest req) {
        LlmProvider exist = providerMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("配置不存在: id=" + id);
        }
        validate(req, id);
        Long dup = providerMapper.selectCount(new LambdaQueryWrapper<LlmProvider>()
                .eq(LlmProvider::getName, req.getName())
                .ne(LlmProvider::getId, id));
        if (dup > 0) {
            throw new IllegalArgumentException("配置名称已存在: " + req.getName());
        }

        copyFromRequest(req, exist);
        if (StringUtils.hasText(req.getApiSpecialKey())) {
            exist.setApiKeyEnc(GewuCrypto.encrypt(req.getApiSpecialKey().trim()));
        } // else 保持原 apiKeyEnc
        exist.setIsActive(Boolean.TRUE.equals(req.getIsActive()));

        providerMapper.updateById(exist);
        deactivateOthersIfNeeded(exist);
        modelProviderService.onConfigChanged();
        log.info("[T3] 更新模型配置: id={}, name={}", id, exist.getName());
        return toView(exist);
    }

    /**
     * 删除配置；激活行删除后回退：chat 侧恢复 yml activeModel（清 DB 激活标记即可，工厂自然回 yml 值）
     */
    @Transactional
    public void delete(Long id) {
        LlmProvider exist = providerMapper.selectById(id);
        if (exist == null) {
            return;
        }
        providerMapper.deleteById(id);
        modelProviderService.onConfigChanged();
        log.info("[T3] 删除模型配置: id={}, name={}, type={}", id, exist.getName(), exist.getProviderType());
    }

    /**
     * 激活指定配置（同 type 下互斥）
     */
    @Transactional
    public void activate(Long id) {
        LlmProvider exist = providerMapper.selectById(id);
        if (exist == null) {
            throw new IllegalArgumentException("配置不存在: id=" + id);
        }
        // 同 type 全部下线
        providerMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getProviderType, exist.getProviderType()))
                .forEach(p -> {
                    if (Boolean.TRUE.equals(p.getIsActive())) {
                        p.setIsActive(false);
                        providerMapper.updateById(p);
                    }
                });
        exist.setIsActive(true);
        providerMapper.updateById(exist);
        modelProviderService.onConfigChanged();
        log.info("[T3] 激活模型配置: type={}, name={}", exist.getProviderType(), exist.getName());
    }

    // ==================== 私有辅助 ====================

    /**
     * 激活行互斥：本行激活时把同 type 其他行下线（uk_llm_provider_active_type 部分唯一索引兜底）
     */
    private void deactivateOthersIfNeeded(LlmProvider entity) {
        if (!Boolean.TRUE.equals(entity.getIsActive())) {
            return;
        }
        providerMapper.selectList(new LambdaQueryWrapper<LlmProvider>()
                        .eq(LlmProvider::getProviderType, entity.getProviderType())
                        .ne(LlmProvider::getId, entity.getId()))
                .forEach(p -> {
                    if (Boolean.TRUE.equals(p.getIsActive())) {
                        p.setIsActive(false);
                        providerMapper.updateById(p);
                    }
                });
    }

    private void copyFromRequest(LlmProviderSaveRequest req, LlmProvider entity) {
        entity.setName(req.getName().trim());
        entity.setProviderType(req.getProviderType());
        entity.setVendor(StringUtils.hasText(req.getVendor()) ? req.getVendor() : "volcengine");
        entity.setBaseUrl(req.getBaseUrl());
        entity.setModelName(req.getModelName());
        entity.setDimension(req.getDimension());
        entity.setMode(req.getMode());
        entity.setTemperature(req.getTemperature());
        entity.setMaxTokens(req.getMaxTokens());
        entity.setDescription(req.getDescription());
    }

    /**
     * 保存前校验（必填与取值域）
     */
    private void validate(LlmProviderSaveRequest req, Long excludeId) {
        if (!StringUtils.hasText(req.getName())) {
            throw new IllegalArgumentException("配置名称必填");
        }
        if (!"chat".equals(req.getProviderType()) && !"embedding".equals(req.getProviderType())) {
            throw new IllegalArgumentException("providerType 必须为 chat 或 embedding");
        }
        if (!StringUtils.hasText(req.getBaseUrl())) {
            throw new IllegalArgumentException("Base URL 必填");
        }
        if (!StringUtils.hasText(req.getModelName())) {
            throw new IllegalArgumentException("模型名称必填");
        }
        if ("embedding".equals(req.getProviderType())
                && !StringUtils.hasText(req.getMode())) {
            // mode 缺省给 multimodal（与 yml 默认一致）
            req.setMode("multimodal");
        }
    }

    /**
     * 实体 → 视图（掩码处理密钥）
     */
    private LlmProviderView toView(LlmProvider entity) {
        LlmProviderView v = new LlmProviderView();
        v.setId(entity.getId());
        v.setName(entity.getName());
        v.setProviderType(entity.getProviderType());
        v.setVendor(entity.getVendor());
        v.setBaseUrl(entity.getBaseUrl());
        String enc = entity.getApiKeyEnc();
        boolean has = StringUtils.hasText(enc);
        v.setHasApiKey(has);
        v.setApiKeyMasked(has ? maskKey(enc) : null);
        v.setModelName(entity.getModelName());
        v.setDimension(entity.getDimension());
        v.setMode(entity.getMode());
        v.setTemperature(entity.getTemperature());
        v.setMaxTokens(entity.getMaxTokens());
        v.setDescription(entity.getDescription());
        v.setIsActive(entity.getIsActive());
        v.setCreateTime(entity.getCreateTime());
        v.setUpdateTime(entity.getUpdateTime());
        return v;
    }

    /**
     * 密钥掩码：密文无法反推明文前缀，统一展示 **** + 尾 4（取密文尾 4 无泄露意义，仅表意「已配置」）；
     * 为避免误导，掩码固定为 sk-****（不展示尾部）
     */
    private String maskKey(String enc) {
        return "sk-****";
    }
}
