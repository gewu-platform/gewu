package com.gewu.common;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM 加密工具（T3 模型配置中心专用，纯 JDK 零依赖）
 *
 * <p>密文格式：{@code v1:{base64(iv)}:{base64(ciphertext+tag)}}，每次加密随机 IV。</p>
 *
 * <p>密钥来源：环境变量 {@code GEWU_ENCRYPT_KEY}（支持 openssl rand -base64 48 输出或任意口令字符串，
 * SHA-256 归一为 32 字节 AES 密钥）。未配置时回退固定开发密钥并仅打日志提示——
 * 生产环境必须在<strong>首次启动前</strong>配置，否则已加密数据无法解密（密钥丢失风险见 PRD §安全）。</p>
 */
public final class GewuCrypto {

    private static final String PREFIX = "v1:";
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private static final String ENV_KEY = "GEWU_ENCRYPT_KEY";
    private static final String DEV_FALLBACK = "gewu-dev-encrypt-key-do-not-use-in-prod";
    private static final SecureRandom RANDOM = new SecureRandom();

    private GewuCrypto() {
    }

    /**
     * 加密明文，返回 v1:iv:ct 密文格式
     */
    public static String encrypt(String plainText) {
        try {
            byte[] iv = new byte[GCM_IV_BYTES];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            return PREFIX
                    + Base64.getEncoder().encodeToString(iv) + ":"
                    + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) {
            throw new IllegalStateException("GEWU 加密失败", e);
        }
    }

    /**
     * 解密 v1: 格式密文；密钥不一致或密文损坏时抛出带明确引导的异常
     */
    public static String decrypt(String stored) {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new IllegalStateException("密文格式非法（缺少 v1: 前缀），可能为历史明文数据");
        }
        try {
            String[] parts = stored.substring(PREFIX.length()).split(":");
            byte[] iv = Base64.getDecoder().decode(parts[0]);
            byte[] ct = Base64.getDecoder().decode(parts[1]);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, resolveKey(), new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解密失败：GEWU_ENCRYPT_KEY 与加密时不一致，或密文已损坏。"
                            + "如密钥已丢失，请在模型设置页重新保存各服务的 API Key", e);
        }
    }

    /**
     * 判断存储串是否为本工具的密文格式
     */
    public static boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX);
    }

    /**
     * 密钥归一：GEWU_ENCRYPT_KEY（Base64 字节或任意口令）→ SHA-256 → 32 字节 AES 密钥；
     * 未配置时回退固定开发密钥（仅限本机开发）。
     */
    private static SecretKey resolveKey() {
        String raw = System.getenv(ENV_KEY);
        String material = (raw == null || raw.isBlank()) ? DEV_FALLBACK : raw.trim();
        byte[] seed;
        try {
            seed = Base64.getDecoder().decode(material);
        } catch (IllegalArgumentException e) {
            seed = material.getBytes(StandardCharsets.UTF_8);
        }
        try {
            byte[] key = MessageDigest.getInstance("SHA-256").digest(seed);
            return new SecretKeySpec(key, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("GEWU 密钥派生失败", e);
        }
    }
}
