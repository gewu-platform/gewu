/*
 * LangChain4j Learning - 数据库建表脚本
 * 
 * 说明：
 * 1. 用于持久化 LangChain4j 会话和消息数据
 * 2. 表结构与 java-ai-native 项目保持一致，便于后续整合
 * 3. 支持 LangChain4j ChatMemoryStore 接口实现
 *
 * 执行方式：
 * mysql -u root -p < langchain4j_learning.sql
 */

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- 创建数据库
CREATE DATABASE IF NOT EXISTS `langchain4j_learning` 
  DEFAULT CHARACTER SET utf8mb4 
  COLLATE utf8mb4_unicode_ci;

USE `langchain4j_learning`;

-- ----------------------------
-- Table structure for chat_session
-- ----------------------------
DROP TABLE IF EXISTS `chat_session`;
CREATE TABLE `chat_session`  (
  `id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NOT NULL COMMENT '会话 ID - 主键，UUID 格式',
  `user_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '用户 ID（预留）- 用于多用户场景',
  `title` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话标题 - 如"第一个问题"',
  `mode` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '模式 - text:文本模式/multimodal:多模态模式',
  `model` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '使用的模型 - 如 qwen-plus, qwen-max 等',
  `system_prompt` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '系统提示词 - 角色设定和对话规则',
  `message_count` int NULL DEFAULT 0 COMMENT '消息数量 - 会话中的消息总数',
  `input_tokens` bigint NULL DEFAULT 0 COMMENT '输入 token 总数 - 累计输入 token 数',
  `output_tokens` bigint NULL DEFAULT 0 COMMENT '输出 token 总数 - 累计输出 token 数',
  `created_at` bigint NULL DEFAULT NULL COMMENT '创建时间戳 - 毫秒级时间戳',
  `updated_at` bigint NULL DEFAULT NULL COMMENT '最后更新时间戳 - 毫秒级时间戳',
  `is_deleted` tinyint NULL DEFAULT 0 COMMENT '是否删除 - 0:未删除/1:已删除（软删除）',
  `remark` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '备注',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE,
  INDEX `idx_updated_at`(`updated_at` ASC) USING BTREE,
  INDEX `idx_user_id`(`user_id` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '聊天会话表 - 存储 AI 对话的会话信息' ROW_FORMAT = Dynamic;

-- ----------------------------
-- Table structure for chat_message
-- ----------------------------
DROP TABLE IF EXISTS `chat_message`;
CREATE TABLE `chat_message`  (
  `id` bigint NOT NULL AUTO_INCREMENT COMMENT '消息 ID - 主键，自增',
  `session_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '会话 ID - 关联 chat_session 表',
  `role` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '角色 - system:系统/user:用户/assistant:AI 助手/tool:工具调用结果',
  `content` text CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL COMMENT '消息内容 - 文本消息内容',
  `content_type` varchar(20) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '内容类型 - text:纯文本/multimodal:多模态',
  `model` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '使用的模型 - 响应时使用的 AI 模型',
  `input_tokens` int NULL DEFAULT NULL COMMENT '本次输入 token 数',
  `output_tokens` int NULL DEFAULT NULL COMMENT '本次输出 token 数',
  `finish_reason` varchar(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT '结束原因 - AI 响应结束的原因',
  `request_id` varchar(64) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci NULL DEFAULT NULL COMMENT 'API 请求 ID',
  `created_at` bigint NULL DEFAULT NULL COMMENT '创建时间戳 - 毫秒级时间戳',
  PRIMARY KEY (`id`) USING BTREE,
  INDEX `idx_session_id`(`session_id` ASC) USING BTREE,
  INDEX `idx_created_at`(`created_at` ASC) USING BTREE,
  INDEX `idx_role`(`role` ASC) USING BTREE
) ENGINE = InnoDB CHARACTER SET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT = '聊天消息表 - 存储 AI 对话的详细消息记录' ROW_FORMAT = Dynamic;

SET FOREIGN_KEY_CHECKS = 1;
