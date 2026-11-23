package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Shared fixture for the ReAct loop tests (single source — the loop's tests
 * used to carry byte-identical constant/mock/script scaffolding, and a second
 * copy of a fixture is exactly how two tests start agreeing on the wrong
 * contract). Holds the project/actor/task ids, the stubbed provider chain and
 * core gateway, the provider answer queue and the request/tool builders.
 * <p>L2 isolation posture is unchanged: the provider chain and the core HTTP
 * gateway are stubbed; assembly, dispatcher and policy stay real objects
 * (orchestration logic is never mocked — the real protocol chain is L4's job).
 */
final class ReActTestHarness {

    static final long PROJECT_ID = 3L;
    static final Long TASK_ID = 42L;
    static final Long ACTOR_ID = 7L;
    static final ObjectMapper MAPPER = new ObjectMapper();
    static final LlmEndpointConfig ENDPOINT =
            new LlmEndpointConfig("mock", "mock-model", null, "http://x", "config");

    final CoreGateway gateway = mock(CoreGateway.class);
    final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    /** Provider answer script (consumed in call order; empty = fixture gap). */
    final Deque<LlmChatResult> script = new ArrayDeque<>();

    /** Wire the stubbed chain onto the script, failing loudly when it runs dry. */
    void scriptChain() {
        when(chain.chatHandle(any(), anyLong(), any())).thenAnswer(inv -> {
            LlmChatResult next = script.poll();
            if (next == null) {
                throw new AssertionError("provider script exhausted (fixture mis-wired)");
            }
            return new LlmChatHandle(next, ENDPOINT);
        });
    }

    static ToolFcAssemblyRemoteItem item(long id, String name, int kind, boolean write) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, kind, "{\"type\":\"object\"}", 5000, write);
    }

    static LlmChatResult withTools(List<LlmToolCall> calls) {
        return new LlmChatResult("", "mock", "mock-model", 10, 20, 1L, calls);
    }

    static LlmChatResult finalAnswer(String content) {
        return new LlmChatResult(content, "mock", "mock-model", 10, 20, 1L, null);
    }

    static ChatCompletionRequest baseRequest(String userText) {
        return ChatCompletionRequest.of("mock-model", List.of(
                new ChatMessage("system", "你是测试工程师"),
                new ChatMessage("user", userText)));
    }
}
