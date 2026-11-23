package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * wiring proof: every provider round of the ReAct loop rides
 * the prompt-budget fitting seam (empty-plan pass included), and the LEGACY
 * constructor keeps byte-compatible no-fit semantics so existing fixture
 * call sites never silently start folding.
 */
class ReActRunnerBudgetFitTest {

    private static final LlmEndpointConfig ENDPOINT = new LlmEndpointConfig(
            "mock", "m", "k", "http://localhost", "DB");

    private static LlmChatHandle answer(String text) {
        return new LlmChatHandle(new LlmChatResult(text, "mock", "m", 1, 1, 5L), ENDPOINT);
    }

    @Test
    void everyRoundIncludingThePlainPassRidesTheFittingSeam() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        when(chain.chatHandle(any(), anyLong(), any())).thenReturn(answer("终答"));
        LlmFunctionCalling functionCalling = mock(LlmFunctionCalling.class);
        when(functionCalling.assemblePlan(anyLong(), any())).thenReturn(FcPlan.EMPTY);
        AtomicInteger fits = new AtomicInteger();
        ChatCompletionRequest fitted = new ChatCompletionRequest("m-fitted",
                List.of(new com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage(
                        "user", "压缩后的包")), null, null);
        LoopContextFitting fitting = (request, projectId) -> {
            fits.incrementAndGet();
            return fitted;
        };
        ReActRunner runner = new ReActRunner(chain, functionCalling,
                mock(ToolDispatcher.class), mock(FcRoundPolicy.class),
                mock(AggregateQueryTool.class), fitting);

        ReActOutcome outcome = runner.run(new ChatCompletionRequest("m",
                List.of(new com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage(
                        "user", "问题")), null, null), 7L, null, null, null, null);

        assertEquals(1, fits.get(), "the plain single call is one provider round too");
        assertEquals("终答", outcome.result().content());
        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(chain).chatHandle(sent.capture(), eq(7L), any());
        assertSame(fitted, sent.getValue(), "the provider receives the FITTED packet");
    }

    @Test
    void legacyConstructorKeepsSendAsBuiltSemantics() {
        LlmFallbackChain chain = mock(LlmFallbackChain.class);
        when(chain.chatHandle(any(), anyLong(), any())).thenReturn(answer("终答"));
        LlmFunctionCalling functionCalling = mock(LlmFunctionCalling.class);
        when(functionCalling.assemblePlan(anyLong(), any())).thenReturn(FcPlan.EMPTY);
        ReActRunner runner = new ReActRunner(chain, functionCalling,
                mock(ToolDispatcher.class), mock(FcRoundPolicy.class),
                mock(AggregateQueryTool.class));
        ChatCompletionRequest base = new ChatCompletionRequest("m",
                List.of(new com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage(
                        "user", "问题")), null, null);

        runner.run(base, 7L, null, null, null, null);

        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(chain).chatHandle(sent.capture(), eq(7L), any());
        assertSame(base, sent.getValue(), "NONE fitting = byte-identical legacy behavior");
    }
}
