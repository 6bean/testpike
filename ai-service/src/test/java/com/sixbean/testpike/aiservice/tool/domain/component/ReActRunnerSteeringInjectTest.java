package com.sixbean.testpike.aiservice.tool.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmStreamEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mid-turn steering consumption : the streaming ReAct
 * loop drains the sink's steering queue at every round boundary BEFORE the
 * next provider call — the injected text joins the conversation as a user
 * message (the model must see it, not just the browser) and the sink journals
 * a consumption marker carrying the step number the supplement landed on.
 * A sink with nothing pending keeps the exact old trajectory (zero drift for
 * every dialog turn nobody steers, and for generator pipelines the port is
 * never polled at all since sink is null).
 * <p>Fixture wiring is shared with {@link ReActRunnerStreamingTest} through
 * {@link ReActStreamHarness}: provider chain mocked, assembly/dispatcher/round
 * policy real.
 */
class ReActRunnerSteeringInjectTest {

    private final ReActStreamHarness fx = new ReActStreamHarness();
    private final LlmFallbackChain chain = fx.chain;

    private static List<LlmStreamEvent> oneDelta(String text) {
        return List.of(new LlmStreamEvent(LlmStreamEvent.KIND_CONTENT, text));
    }

    @Test
    void boundaryInjectJoinsMessagesBeforeTheNextProviderCall() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
        when(fx.gateway.invokeTool(eq(fx.actorId), eq(fx.taskId), eq(11L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"data\":1}", null, null, null, 120L));
        fx.script(oneDelta(""), fx.withTools(List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));
        fx.script(oneDelta("已定位"), fx.finalAnswer("已定位"));

        ReActSteeringSink sink = new ReActSteeringSink();
        sink.pollScript.add(List.of("改用中文回答"));
        sink.pollScript.add(List.of());

        fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        ArgumentCaptor<ChatCompletionRequest> requests =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, times(2)).chatStreamHandle(requests.capture(), anyLong(), any(), any());
        List<ChatMessage> firstCallMessages = requests.getAllValues().get(0).messages();
        assertTrue(firstCallMessages.contains(new ChatMessage("user", "改用中文回答")),
                "the supplement is a real user message the provider sees, not just a journaled frame");
        assertEquals(List.of("inject#1:改用中文回答"), sink.steerLog,
                "consumed at the boundary before round 1 = step 1");
    }

    @Test
    void lateInjectBetweenRoundsCarriesTheNextStepNumber() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
        when(fx.gateway.invokeTool(eq(fx.actorId), eq(fx.taskId), eq(11L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"data\":1}", null, null, null, 120L));
        fx.script(oneDelta(""), fx.withTools(List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));
        fx.script(oneDelta("已定位"), fx.finalAnswer("已定位"));

        ReActSteeringSink sink = new ReActSteeringSink();
        sink.pollScript.add(List.of());
        sink.pollScript.add(List.of("重点看接口超时"));

        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals(List.of("inject#2:重点看接口超时"), sink.steerLog,
                "consumed after round 1 = the model continues from step 2");
        ArgumentCaptor<ChatCompletionRequest> requests =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, times(2)).chatStreamHandle(requests.capture(), anyLong(), any(), any());
        List<ChatMessage> secondCallMessages = requests.getAllValues().get(1).messages();
        assertTrue(secondCallMessages.contains(new ChatMessage("user", "重点看接口超时")),
                "the late supplement joins the conversation before the next provider call");
        assertEquals("已定位", outcome.result().content());
    }

    @Test
    void emptyPollKeepsTheExactPreSteeringTrajectory() {
        when(fx.gateway.getProjectFcAssembly(fx.projectId, fx.actorId))
                .thenReturn(List.of(fx.toolItem(11, "kb_http", 1, false)));
        when(fx.gateway.invokeTool(eq(fx.actorId), eq(fx.taskId), eq(11L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"data\":1}", null, null, null, 120L));
        fx.script(oneDelta(""), fx.withTools(List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));
        fx.script(oneDelta("已定位"), fx.finalAnswer("已定位"));

        ReActSteeringSink sink = new ReActSteeringSink();
        ReActOutcome outcome = fx.runner().runStreaming(fx.baseRequest("分析这批失败"),
                fx.projectId, null, fx.actorId, fx.taskId, null, sink);

        assertEquals(List.of(), sink.steerLog);
        assertEquals(1, outcome.rounds());
        ArgumentCaptor<ChatCompletionRequest> requests =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(chain, times(2)).chatStreamHandle(requests.capture(), anyLong(), any(), any());
        List<ChatMessage> firstCallMessages = requests.getAllValues().get(0).messages();
        assertEquals(2, firstCallMessages.size(),
                "system + user = exactly the old shape, no phantom user message "
                        + "when nobody steered");
    }
}
