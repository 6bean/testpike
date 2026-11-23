package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatResponseFormat;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.mcp.domain.component.FakeMcpToolSession;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag;
import com.sixbean.testpike.aiservice.mcp.domain.component.ToolInvocationRecorder;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import org.junit.jupiter.api.Test;

import java.util.Deque;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.baseRequest;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.finalAnswer;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.item;
import static com.sixbean.testpike.aiservice.tool.domain.component.ReActTestHarness.withTools;


class ReActRunnerTest {

    private final ReActTestHarness fx = new ReActTestHarness();
    private final CoreGateway gateway = fx.gateway;
    private final LlmFallbackChain chain = fx.chain;
    /** Provider answer script (shared fixture, consumed in call order). */
    private final Deque<LlmChatResult> script = fx.script;
    private static final long PROJECT_ID = ReActTestHarness.PROJECT_ID;
    private static final Long TASK_ID = ReActTestHarness.TASK_ID;
    private static final Long ACTOR_ID = ReActTestHarness.ACTOR_ID;
    private static final ObjectMapper MAPPER = ReActTestHarness.MAPPER;

    private ReActRunner runner() {
        return new ReActRunner(chain, new LlmFunctionCalling(gateway, MAPPER),
                new ToolDispatcher(gateway, MAPPER), new FcRoundPolicy(gateway),
                new AggregateQueryTool(gateway, MAPPER));
    }






    
    @Test
    void 空计划直通零增补() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of());
        fx.scriptChain();
        script.add(finalAnswer("普通输出"));
        ReActOutcome outcome = runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);
        assertEquals("普通输出", outcome.result().content());
        assertEquals(0, outcome.rounds());
        assertEquals(0, outcome.dispatched());
        assertEquals(2, outcome.messages().size(), "对话零增补");
        assertFalse(outcome.limitReached());
    }

    
    @Test
    void 工具调用回填后继续出终答() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, "kb_http", 1, false)));
        when(gateway.invokeTool(eq(ACTOR_ID), eq(TASK_ID), eq(11L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"data\":1}", null, null, null, 120L));
        fx.scriptChain();
        script.add(withTools(List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));
        script.add(finalAnswer("已根据工具数据完成用例"));

        ReActOutcome outcome = runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals("已根据工具数据完成用例", outcome.result().content());
        assertEquals(1, outcome.rounds());
        assertEquals(1, outcome.dispatched());
        List<ChatMessage> msgs = outcome.messages();
        assertEquals(4, msgs.size(), "system+user+assistant+tool（终答不重复回写）");
        ChatMessage assistant = msgs.get(2);
        assertEquals("assistant", assistant.role());
        assertEquals("c1", assistant.toolCalls().get(0).id(), "tool_calls 回写锚点");
        assertEquals("{\"q\":\"登录\"}", assistant.toolCalls().get(0).function().arguments(),
                "arguments 原文回写禁二次对象化");
        ChatMessage tool = msgs.get(3);
        assertEquals("tool", tool.role());
        assertEquals("c1", tool.toolCallId(), "tool_call_id 关联键");
        assertEquals("{\"data\":1}", tool.content(), "工具输出原文进 tool 消息");
        ChatCompletionRequest secondaryEndpoint = captureRequest(2);
        assertTrue(secondaryEndpoint.messages().size() >= 4, "第链路二带上回填序列");
        assertTrue(secondaryEndpoint.messages().stream().anyMatch(m -> "tool".equals(m.role())));
    }

    
    @Test
    void 有工具清单时请求挂tools与契约位() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, "kb_http", 1, false)));
        fx.scriptChain();
        script.add(finalAnswer("终答"));
        runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);
        ChatCompletionRequest first = captureRequest(1);
        assertEquals(1, first.tools().size());
        assertEquals("kb_http", first.tools().get(0).function().name());
        assertTrue(first.messages().get(0).content().contains("FC_TOOL_CALL"), "契约位注入");
    }

    
    @Test
    void mcp桥工具经任务袋执行() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(13, "mcp_bridge", 3, false)));
        when(gateway.getMcpAssemblyServers(anyLong())).thenReturn(List.of(
                new McpAssemblyRemoteServer(9L, "bridge", "http://127.0.0.1:1/mcp", 2, null,
                        List.of("mcp_bridge"))));
        McpTaskToolBag bag = new McpClientPool(gateway, new ToolInvocationRecorder(gateway),
                s -> new FakeMcpToolSession(s, null)).openTask(TASK_ID, PROJECT_ID);
        fx.scriptChain();
        script.add(withTools(List.of(new LlmToolCall("c1", "mcp_bridge", "{\"q\":\"kb\"}"))));
        script.add(finalAnswer("桥接数据已消费"));

        ReActOutcome outcome = runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, bag);

        assertEquals(1, outcome.dispatched());
        assertEquals(1, bag.invocationTraces().size(), "袋内留痕计数");
        assertEquals("桥接数据已消费", outcome.result().content());
    }

    
    @Test
    void 轮次超限拒续取末次输出() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, "kb_http", 1, false)));
        when(gateway.invokeTool(any(), any(), anyLong(), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true, "o", null, null, null, 1L));
        when(gateway.getSysConfig(FcRoundPolicy.KEY)).thenReturn("2");
        fx.scriptChain();
        script.add(withTools(List.of(new LlmToolCall("c1", "kb_http", "{}"))));
        script.add(withTools(List.of(new LlmToolCall("c2", "kb_http", "{}"))));

        ReActOutcome outcome = runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertTrue(outcome.limitReached(), "超限标记（调用方人话可观测）");
        assertEquals(2, outcome.rounds(), "恰好执行上限轮次，无第 3 轮请求");
        assertEquals(2, outcome.dispatched());
        assertEquals("", outcome.result().content(), "取末次输出不抛");
    }

    
    @Test
    void 写语义工具默认拒循环继续() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(14, "write_tool", 1, true)));
        fx.scriptChain();
        script.add(withTools(List.of(new LlmToolCall("c1", "write_tool", "{}"))));
        script.add(finalAnswer("写操作已被拒，改用只读方案"));

        ReActOutcome outcome = runner().run(ReActTestHarness.baseRequest("登录需求"), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(0, outcome.dispatched());
        assertEquals(1, outcome.rejected());
        assertEquals("写操作已被拒，改用只读方案", outcome.result().content());
        assertTrue(outcome.messages().get(3).content().contains("x-write"),
                "拒因人话进 tool 消息");
    }

    
    @Test
    void 轮次策略K8热更与回退() {
        FcRoundPolicy policy = new FcRoundPolicy(gateway);
        when(gateway.getSysConfig(FcRoundPolicy.KEY)).thenReturn("3");
        assertEquals(3, policy.maxRounds());
        when(gateway.getSysConfig(FcRoundPolicy.KEY)).thenReturn(null);
        assertEquals(5, policy.maxRounds(), "无配置回退默认");
        when(gateway.getSysConfig(FcRoundPolicy.KEY)).thenReturn("abc");
        assertEquals(5, policy.maxRounds(), "非法回退默认");
        when(gateway.getSysConfig(FcRoundPolicy.KEY)).thenReturn("0");
        assertEquals(5, policy.maxRounds(), "非正数回退默认");
    }

    /** ⑦ : a response format pinned on the base request must survive every
 * per-turn rebuild of the FC loop. The legacy six-arg constructor used to
 * drop it silently, so schema-constrained decoding never rode the wire
 * whenever the ReAct runner was present (live window w13b: refusal-warns=0
 * with a title-less draft = the block never reached the endpoint). */
    @Test
    void structuredOutputBlockSurvivesEveryLoopTurn() throws Exception {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID))
                .thenReturn(List.of(item(11, "kb_http", 1, false)));
        when(gateway.invokeTool(any(), any(), anyLong(), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true, "o", null, null, null, 1L));
        ChatResponseFormat format = ChatResponseFormat.jsonSchema(
                "testpike_case_draft_v1", true,
                MAPPER.createObjectNode().put("type", "object"));
        fx.scriptChain();
        script.add(withTools(List.of(new LlmToolCall("c1", "kb_http", "{}"))));
        script.add(finalAnswer("终答"));

        runner().run(ReActTestHarness.baseRequest("登录需求").withResponseFormat(format),
                PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        var captor = org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.times(2))
                .chatHandle(captor.capture(), anyLong(), any());
        List<ChatCompletionRequest> sent = captor.getAllValues();
        assertEquals("testpike_case_draft_v1",
                sent.get(0).responseFormat().jsonSchema().name(),
                "turn 1 must carry the constrained-decoding block");
        assertEquals("testpike_case_draft_v1",
                sent.get(1).responseFormat().jsonSchema().name(),
                "every rebuilt turn keeps the block until the final answer");
    }


    private ChatCompletionRequest captureRequest(int callIndex) {
        var captor = org.mockito.ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.times(callIndex))
                .chatHandle(captor.capture(), anyLong(), any());
        return captor.getAllValues().get(callIndex - 1);
    }
}
