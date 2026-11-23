package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest
@TestPropertySource(properties = {
        "llm.mock-enabled=true",
        "llm.provider=openai"
})
class LlmProviderConfigMockEnabledTest {

    @Autowired
    private LlmProvider llmProvider;

    @Autowired
    private LlmProviderFactory factory;

    @Test
    void mockEnabled为true_装配本地替身() {
        assertTrue(factory.isMock());
        assertEquals("mock", factory.currentProviderName());
        assertEquals("mock", llmProvider.providerName());
    }
}
