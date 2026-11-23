package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;


@SpringBootTest
@TestPropertySource(properties = {
        "llm.mock-enabled=false",
        "llm.provider=openai",
        "llm.base-url=http://localhost:18080/v1",
        "llm.api-key=sk-test-env-key"
})
class LlmProviderConfigRealTest {

    @Autowired
    private LlmProvider llmProvider;

    @Autowired
    private LlmProviderFactory factory;

    @Test
    void mockEnabled为false_装配真实Provider() {
        assertFalse(factory.isMock());
        assertEquals("openai", factory.currentProviderName());
        assertEquals("openai", llmProvider.providerName());
    }
}
