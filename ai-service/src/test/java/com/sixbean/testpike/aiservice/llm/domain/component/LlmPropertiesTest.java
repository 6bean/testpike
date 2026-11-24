package com.sixbean.testpike.aiservice.llm.domain.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class LlmPropertiesTest {

    @Test
    void 默认值_本地替身开启_供应商openai() {
        LlmProperties p = new LlmProperties();
        assertTrue(p.isMockEnabled());
        assertEquals("openai", p.getProvider());
        assertEquals("gpt-4o-mini", p.getModel());
        assertEquals(800, p.getTimeoutSeconds());
    }

    @Test
    void 密钥setter注入_不进入默认toString() {
        LlmProperties p = new LlmProperties();
        p.setApiKey("sk-SECRET-ABC-123456");
        assertFalse(p.toString().contains("sk-SECRET-ABC-123456"));
        assertEquals("sk-SECRET-ABC-123456", p.getApiKey());
    }

    @Test
    void 配置切换_供应商与模型可改() {
        LlmProperties p = new LlmProperties();
        p.setProvider("deepseek");
        p.setModel("deepseek-chat");
        p.setBaseUrl("https://api.deepseek.com/v1");
        p.setMockEnabled(false);
        assertFalse(p.isMockEnabled());
        assertEquals("deepseek", p.getProvider());
        assertEquals("deepseek-chat", p.getModel());
        assertEquals("https://api.deepseek.com/v1", p.getBaseUrl());
    }
}
