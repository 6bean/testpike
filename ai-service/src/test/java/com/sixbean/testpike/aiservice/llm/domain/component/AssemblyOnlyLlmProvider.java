package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.service.LlmProvider;

/**
 * Provider slot used only to satisfy {@link LlmProviderFactory} assembly in
 * real-mode suites. Those suites exercise the real HTTP client path against a
 * local stand-in endpoint, so this provider's chat() is never reached; calling
 * it fails loudly instead of silently faking a response.
 */
class AssemblyOnlyLlmProvider implements LlmProvider {

    @Override
    public String providerName() {
        return "openai";
    }

    @Override
    public LlmChatResult chat(ChatCompletionRequest request) {
        throw new UnsupportedOperationException("these suites only exercise the real client path");
    }
}
