package com.gewu.module.chat.controller;

import com.gewu.client.LangChain4jConfig;
import com.gewu.common.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * V2.5.0 M1.3：图片能力门禁测试。
 *
 * <p>验证口径：无图片时任何模型都放行；有图片时仅 multimodal 放行，
 * 纯文本模型抛可读的 BizException（中文提示含模型名与处置建议），
 * 而不是把图片发给模型换来晦涩的 API 报错。</p>
 */
class ChatControllerCapabilityTest {

    private LangChain4jConfig config;
    private ChatController controller;

    @BeforeEach
    void setUp() throws Exception {
        config = mock(LangChain4jConfig.class);
        // 用反射构造，避免为测试补齐全部 6 个依赖
        var ctor = ChatController.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i].isAssignableFrom(LangChain4jConfig.class) ? config : mock(types[i]);
        }
        ctor.setAccessible(true);
        controller = (ChatController) ctor.newInstance(args);
    }

    private LangChain4jConfig.ModelConfig cfg(String capability) {
        LangChain4jConfig.ModelConfig c = new LangChain4jConfig.ModelConfig();
        c.setCapability(capability);
        return c;
    }

    @Test
    void 无图片时纯文本模型放行() {
        when(config.getModels()).thenReturn(Map.of("text-model", cfg("text")));
        assertThatCode(() -> controller.assertImageCapability("text-model", null))
                .doesNotThrowAnyException();
        assertThatCode(() -> controller.assertImageCapability("text-model", List.of()))
                .doesNotThrowAnyException();
    }

    @Test
    void 有图片时纯文本模型被拦下() {
        when(config.getModels()).thenReturn(Map.of("text-model", cfg("text")));
        assertThatThrownBy(() -> controller.assertImageCapability("text-model", List.of("chat/a.png")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持图片输入")
                .hasMessageContaining("text-model")
                .hasMessageContaining("multimodal");
    }

    @Test
    void 有图片时视觉模型放行() {
        when(config.getModels()).thenReturn(Map.of("vl-model", cfg("multimodal")));
        assertThatCode(() -> controller.assertImageCapability("vl-model", List.of("chat/a.png")))
                .doesNotThrowAnyException();
    }

    @Test
    void 能力标记大小写不敏感() {
        when(config.getModels()).thenReturn(Map.of("vl", cfg("MultiModal")));
        assertThatCode(() -> controller.assertImageCapability("vl", List.of("chat/a.png")))
                .doesNotThrowAnyException();
    }

    @Test
    void capability默认值为text() {
        LangChain4jConfig.ModelConfig c = new LangChain4jConfig.ModelConfig();
        // 未显式配置时默认纯文本——安全默认：宁可拦下并提示，也不盲目把图发给非视觉模型
        if (c.getCapability() == null) {
            // 若为 null 也应视为不支持
            org.assertj.core.api.Assertions.assertThat(c.supportsImage()).isFalse();
        } else {
            org.assertj.core.api.Assertions.assertThat(c.getCapability()).isEqualTo("text");
            org.assertj.core.api.Assertions.assertThat(c.supportsImage()).isFalse();
        }
    }

    @Test
    void 未知模型或配置缺失视为不支持图片() {
        when(config.getModels()).thenReturn(Map.of());
        // 未知模型在更早的校验已被拦下；此处兜底也应拒绝而非放行
        assertThatThrownBy(() -> controller.assertImageCapability("ghost", List.of("chat/a.png")))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不支持图片输入");
    }
}
