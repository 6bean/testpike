package com.sixbean.testpike.core.llmconfig.api.dto;

import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Contract: LlmConfigBrief carries the decrypted provider key for internal
 * ai-service routing, so its toString must never print the plaintext —
 * assertion failures and surefire/log dumps render records via toString,
 * and a raw key there leaks credentials into build logs (AGENTS red line 4,
 * observed leaking two decrypted rows on 2026-11-20). The masked rendering
 * keeps every other field readable for debugging.
 */
class LlmConfigBriefMaskingTest {

    @Test
    void toString_never_prints_decrypted_api_key() {
        LlmConfigBrief brief = new LlmConfigBrief(4576L, "slot-default-gpt", "openai",
                "gpt-4o", "sk-PLAINTEXT-SECRET-123", "https://api.openai.com/v1", "default",
                1048576, "gpt", 1, null, true, true);

        String rendered = brief.toString();

        assertFalse(rendered.contains("sk-PLAINTEXT-SECRET-123"),
                "toString must not carry the plaintext key");
        assertTrue(rendered.contains("apiKey=" + ConfigCipher.MASK),
                "key field renders as the shared mask constant");
        assertTrue(rendered.contains("slot-default-gpt") && rendered.contains("gpt-4o"),
                "non-secret fields stay readable for debugging");
    }

    @Test
    void toString_keeps_null_key_rendered_as_null() {
        LlmConfigBrief brief = new LlmConfigBrief(1L, "local-gateway", "openai",
                "qwen", null, "http://localhost:4000/v1", "default",
                null, null, 1, null, null, null);

        String rendered = brief.toString();

        assertTrue(rendered.contains("apiKey=null"),
                "absent key stays honest — null, not a fake mask");
    }
}
