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
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Consumer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Single-source L2 fixture for the streaming ReAct loop tests. The round
 * scripts, the provider-chain stub, the runner wiring and the payload builders
 * were previously copied verbatim into every streaming test class — the same
 * logic with a second consumer — so they live here once. Isolation posture is
 * unchanged from those tests: the provider chain (infrastructure HTTP) is
 * mocked while assembly/dispatcher/round-policy stay real, and the core door
 * is either a mocked gateway or a caller-supplied real one. Composed by the
 * tests (no inheritance, no inner types).
 */
final class ReActStreamHarness {

    final long projectId = 3L;
    final Long taskId = 42L;
    final Long actorId = 7L;
    final ObjectMapper mapper = new ObjectMapper();
    final LlmEndpointConfig endpoint =
            new LlmEndpointConfig("mock", "mock-model", null, "http://x", "config");

    /** Mocked by default; the dialog-surface tests pass a real client over a local stub. */
    final CoreGateway gateway;
    final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    /** Per-call delta scripts consumed in call order (missing entry = fixture bug). */
    final Deque<List<LlmStreamEvent>> streamScript = new ArrayDeque<>();
    /** Aggregated results the mocked provider chain returns after streaming. */
    final Deque<LlmChatResult> resultScript = new ArrayDeque<>();

    ReActStreamHarness() {
        this(mock(CoreGateway.class));
    }

    ReActStreamHarness(CoreGateway gateway) {
        this.gateway = gateway;
        when(chain.chatStreamHandle(any(), anyLong(), any(), any()))
                .thenAnswer(invocation -> {
                    List<LlmStreamEvent> events = streamScript.poll();
                    LlmChatResult result = resultScript.poll();
                    if (events == null || result == null) {
                        throw new AssertionError("stream script exhausted (fixture misconfigured)");
                    }
                    Consumer<LlmStreamEvent> onEvent = invocation.getArgument(3);
                    events.forEach(onEvent);
                    return new LlmChatHandle(result, endpoint);
                });
    }

    /** Runner wired with the real assembly/dispatch/round-policy components. */
    ReActRunner runner() {
        return new ReActRunner(chain, new LlmFunctionCalling(gateway, mapper),
                new ToolDispatcher(gateway, mapper), new FcRoundPolicy(gateway),
                new AggregateQueryTool(gateway, mapper));
    }

    /** Content-kind deltas in provider order (no pieces = a silent round). */
    List<LlmStreamEvent> deltas(String... pieces) {
        List<LlmStreamEvent> list = new ArrayList<>();
        for (String p : pieces) {
            list.add(new LlmStreamEvent(LlmStreamEvent.KIND_CONTENT, p));
        }
        return list;
    }

    /** Assembled tool row as the project catalog delivers it. */
    ToolFcAssemblyRemoteItem toolItem(long id, String name, int kind, boolean write) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, kind,
                "{\"type\":\"object\"}", 5000, write);
    }

    /** Provider answer that dispatches tool calls instead of answering. */
    LlmChatResult withTools(List<LlmToolCall> calls) {
        return new LlmChatResult("", "mock", "mock-model", 10, 20, 1L, calls);
    }

    /** Provider answer carrying the final assistant text. */
    LlmChatResult finalAnswer(String content) {
        return new LlmChatResult(content, "mock", "mock-model", 10, 20, 1L, null);
    }

    /** Conversation as the dialog surface hands it to the loop. */
    ChatCompletionRequest baseRequest(String userText) {
        return ChatCompletionRequest.of("mock-model", List.of(
                new ChatMessage("system", "你是测试工程师"),
                new ChatMessage("user", userText)));
    }

    /** Scripted round: the deltas the provider streams, then its aggregated answer. */
    void script(List<LlmStreamEvent> events, LlmChatResult result) {
        streamScript.add(events);
        resultScript.add(result);
    }
}
