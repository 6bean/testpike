package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReAct wiring for the retrieval meta-tool : the built-in
 * rides every FC plan that actually runs (mounted after the empty-plan gate,
 * so legacy plain-completion stays byte-identical) and a tool_call named
 * {@code retrieval} routes straight to the built-in executor — never through
 * the registered-tool dispatcher, same posture as aggregate.query and
 * plan.propose on the dialog surface.
 */
class ReActRunnerRetrievalWiringTest {

    private static final long PROJECT_ID = 3L;
    private static final Long TASK_ID = 42L;
    private static final Long ACTOR_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmEndpointConfig ENDPOINT =
            new LlmEndpointConfig("mock", "mock-model", null, "http://x", "config");

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    private final ToolResultRetrievalTool retrieval = mock(ToolResultRetrievalTool.class);
    private final Deque<LlmChatResult> script = new ArrayDeque<>();

    private ReActRunner runnerWithRetrieval() {
        return new ReActRunner(chain, new LlmFunctionCalling(gateway, MAPPER),
                new ToolDispatcher(gateway, MAPPER), new FcRoundPolicy(gateway),
                new AggregateQueryTool(gateway, MAPPER),
                com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting.NONE,
                retrieval);
    }

    private void scriptChain() {
        when(chain.chatHandle(any(), anyLong(), any())).thenAnswer(inv -> {
            LlmChatResult next = script.poll();
            if (next == null) {
                throw new AssertionError("provider script exhausted (fixture misconfigured)");
            }
            return new LlmChatHandle(next, ENDPOINT);
        });
    }

    private static ChatCompletionRequest baseRequest() {
        return ChatCompletionRequest.of("mock-model", List.of(
                new ChatMessage("system", "你是测试工程师"),
                new ChatMessage("user", "回取全文")));
    }

    @Test
    void retrievalRidesRunningPlanAndRoutesToBuiltinNotDispatcher() throws Exception {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                new ToolFcAssemblyRemoteItem(1L, "已注册工具", 2, 1,
                        "{\"type\":\"object\"}", 5000, false)));
        when(retrieval.toolDef()).thenReturn(new ChatToolDef(new ChatToolFunction(
                ToolResultRetrievalTool.NAME, "回取全文", MAPPER.readTree("{\"type\":\"object\"}"))));
        when(retrieval.execute(eq("{\"anchor\":\"k\"}"), eq(TASK_ID), eq(PROJECT_ID)))
                .thenReturn(ToolCallOutcome.ok(ToolResultRetrievalTool.NAME, "FULLTEXT-MOCK"));
        scriptChain();
        script.add(new LlmChatResult("", "mock", "mock-model", 10, 20, 1L,
                List.of(new LlmToolCall("c1", "retrieval", "{\"anchor\":\"k\"}"))));
        script.add(new LlmChatResult("终答", "mock", "mock-model", 10, 20, 1L, null));

        ReActOutcome outcome = runnerWithRetrieval().run(baseRequest(), PROJECT_ID, null,
                ACTOR_ID, TASK_ID, null);

        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, org.mockito.Mockito.times(2)).chatHandle(req.capture(), anyLong(), any());
        assertTrue(req.getAllValues().get(1).tools().stream()
                        .anyMatch(t -> ToolResultRetrievalTool.NAME.equals(t.function().name())),
                "retrieval must ride the plan of a running FC loop");
        assertTrue(outcome.messages().stream()
                        .anyMatch(m -> "tool".equals(m.role())
                                && "FULLTEXT-MOCK".equals(m.content())),
                "built-in executor result backfills the tool message verbatim");
        verify(retrieval).execute("{\"anchor\":\"k\"}", TASK_ID, PROJECT_ID);
        assertEquals(1, outcome.dispatched());
        assertEquals("终答", outcome.result().content());
    }

    @Test
    void emptyPlanPlainCompletionMountsNothingEvenWithBeanPresent() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of());
        scriptChain();
        script.add(new LlmChatResult("普通输出", "mock", "mock-model", 10, 20, 1L, null));

        ReActOutcome outcome = runnerWithRetrieval().run(baseRequest(), PROJECT_ID, null,
                ACTOR_ID, TASK_ID, null);

        ArgumentCaptor<ChatCompletionRequest> req =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain).chatHandle(req.capture(), anyLong(), any());
        assertNull(req.getValue().tools(),
                "legacy empty-plan semantics must not grow a tools segment");
        verify(retrieval, never()).toolDef();
        assertEquals(0, outcome.rounds());
    }
}
