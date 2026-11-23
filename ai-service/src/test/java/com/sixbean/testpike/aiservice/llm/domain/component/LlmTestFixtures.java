package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Shared L2 fixtures for the LLM component suites: real-mode properties and
 * factory assembly, plus the OpenAI-compatible chat-completion response body
 * template served by the local stand-in servers. Single source so wire-level
 * assertions and fallback semantics stay identical across suites (no copy of
 * the assembly block or response body per test class).
 */
final class LlmTestFixtures {

    private LlmTestFixtures() {
    }

    /** Properties in real mode (mockEnabled=false): the client path runs real HTTP. */
    static LlmProperties realProps() {
        LlmProperties p = new LlmProperties();
        p.setMockEnabled(false);
        return p;
    }

    /**
     * Real-mode factory: chatFor/chatStreamFor run over the real RestClient against
     * a local stand-in endpoint; the provider slot only satisfies assembly (the
     * isMock decision keys off providerName).
     */
    static LlmProviderFactory realFactory(ObjectMapper mapper) {
        return new LlmProviderFactory(new AssemblyOnlyLlmProvider(), realProps(), mapper);
    }

    /** OpenAI-compatible non-streaming completion body carrying the given assistant content. */
    static String chatCompletionBody(String content) {
        return "{\"choices\":[{\"message\":{\"content\":\"" + content + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":2}}";
    }

    /** Serve a JSON completion body (200 + application/json) on a JDK HttpExchange. */
    static void respondJson(HttpExchange exchange, String content) throws IOException {
        byte[] body = chatCompletionBody(content).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(body);
        }
    }
}
