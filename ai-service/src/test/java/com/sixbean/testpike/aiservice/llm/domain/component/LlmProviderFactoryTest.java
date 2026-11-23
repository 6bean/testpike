package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.service.impl.MockLlmProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmProviderFactoryTest {

    private LlmProviderFactory factory(LlmProperties p) {
        return new LlmProviderFactory(new MockLlmProvider("m"), p, new ObjectMapper());
    }

    @Test
    void mock替身_当前供应商名为mock() {
        LlmProperties p = new LlmProperties();
        LlmProviderFactory factory = factory(p);
        assertEquals("mock", factory.currentProviderName());
        assertTrue(factory.isMock());
    }

    @Test
    void 调用透传_结果来自当前供应商() {
        LlmProperties p = new LlmProperties();
        LlmProviderFactory factory = factory(p);
        var result = factory.chat(new ChatCompletionRequest("m",
                List.of(new ChatMessage("user", "生成用例")), null, null));
        assertTrue(result.content().startsWith("【mock 生成】"));
        assertFalse(result.content().isEmpty());
    }

    @Test
    void chatFor_mock模式_忽略路由端点走本地替身_K9路由() {
        LlmProperties p = new LlmProperties();
        p.setModel("config-model");
        LlmProviderFactory factory = factory(p);
        LlmEndpointConfig endpoint = new LlmEndpointConfig("deepseek", "deepseek-chat",
                "sk-project", "https://api.deepseek.com/v1", LlmEndpointConfig.SOURCE_PROJECT);

        var result = factory.chatFor(new ChatCompletionRequest("deepseek-chat",
                List.of(new ChatMessage("user", "生成用例")), null, null), endpoint);
        assertTrue(result.content().startsWith("【mock 生成】"));
        assertEquals("deepseek-chat", endpoint.modelName(), "请求模型取路由结果（TC-K9-02）");
    }
}
