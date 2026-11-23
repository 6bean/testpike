package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LoopContextFitting;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayDeque;
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

/**
 * ReAct loop wiring for the AP-5 catalog meta-tool : a
 * {@code search_tools} tool_call runs inside the loop (built-in posture —
 * never through the registered-tool dispatcher, same as aggregate.query /
 * plan.propose / retrieval), and every materialized definition JOINs the
 * mutable resident list so the NEXT provider round's tools[] actually carries
 * the expanded tool. The deferred pool is consumed on expansion — a second
 * search for the same capability honestly reports "no match" instead of
 * re-shipping definitions.
 * <p>L2 isolation (ReActRunnerTest approved posture): provider chain and the
 * core HTTP door are mocked infrastructure; assembly/catalog/dispatcher/
 * round-policy are the real components (orchestration body never mocked).
 */
class ReActRunnerCatalogSearchWiringTest {

    private static final long PROJECT_ID = 3L;
    private static final Long TASK_ID = 42L;
    private static final Long ACTOR_ID = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final LlmEndpointConfig ENDPOINT =
            new LlmEndpointConfig("mock", "mock-model", null, "http://x", "config");

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final LlmFallbackChain chain = mock(LlmFallbackChain.class);
    private final Deque<LlmChatResult> script = new ArrayDeque<>();

    /** Catalog mode: threshold 1 (2 candidates > 1), kb_http core-resident. */
    private void stubCatalogKeys() {
        when(gateway.getSysConfig(FcToolCatalog.KEY_THRESHOLD)).thenReturn("1");
        when(gateway.getSysConfig(FcToolCatalog.KEY_CORE_TOOLS)).thenReturn("kb_http");
        when(gateway.getSysConfig(FcToolCatalog.KEY_EXPAND_MAX)).thenReturn("8");
    }

    private ReActRunner runner() {
        FcToolCatalog catalog = new FcToolCatalog(gateway, MAPPER);
        return new ReActRunner(chain, new LlmFunctionCalling(gateway, MAPPER, catalog),
                new ToolDispatcher(gateway, MAPPER), new FcRoundPolicy(gateway),
                new AggregateQueryTool(gateway, MAPPER), LoopContextFitting.NONE,
                null, catalog);
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

    private static ToolFcAssemblyRemoteItem item(long id, String name, String schema) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, 1, schema, 5000, false);
    }

    private static JsonNode node(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static LlmChatResult toolCall(LlmToolCall... calls) {
        return new LlmChatResult("", "mock", "mock-model", 10, 20, 1L, List.of(calls));
    }

    private static LlmChatResult finalAnswer(String content) {
        return new LlmChatResult(content, "mock", "mock-model", 10, 20, 1L, null);
    }

    private static ChatCompletionRequest baseRequest() {
        return ChatCompletionRequest.of("mock-model", List.of(
                new ChatMessage("system", "你是测试工程师"),
                new ChatMessage("user", "查用例明细")));
    }

    private static List<String> toolNames(ChatCompletionRequest request) {
        return request.tools() == null ? List.of()
                : request.tools().stream().map(t -> t.function().name()).toList();
    }

    private List<ChatCompletionRequest> capturedRounds() {
        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        org.mockito.Mockito.verify(chain, org.mockito.Mockito.atLeastOnce())
                .chatHandle(captor.capture(), anyLong(), any());
        return captor.getAllValues();
    }

    /** ① search_tools expands the resident list: round-2 tools[] carries the tool. */
    @Test
    void searchToolsExpansionRidesNextRoundRequest() {
        stubCatalogKeys();
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(11, "kb_http", "{\"type\":\"object\"}"),
                item(12, "ext_detail",
                        "{\"type\":\"object\",\"description\":\"查询用例步骤明细\"}")));
        when(gateway.invokeTool(eq(ACTOR_ID), eq(TASK_ID), eq(12L), any(), isNull(), any()))
                .thenReturn(new ToolInvokeResultRemote(12L, "ext_detail", true,
                        "{\"steps\":3}", null, null, null, 90L));
        scriptChain();
        script.add(toolCall(new LlmToolCall("c1", FcToolCatalog.NAME, "{\"query\":\"ext_\"}")));
        script.add(toolCall(new LlmToolCall("c2", "ext_detail", "{\"id\":3}")));
        script.add(finalAnswer("明细已取回"));

        var result = runner().run(baseRequest(), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        List<ChatCompletionRequest> rounds = capturedRounds();
        assertEquals(3, rounds.size(), "search 轮 + 展开调用轮 + 终答轮");
        assertTrue(toolNames(rounds.get(0)).contains(FcToolCatalog.NAME),
                "目录态首轮挂 search_tools");
        assertFalse(toolNames(rounds.get(0)).contains("ext_detail"),
                "目录化候选首轮不常驻");
        assertTrue(toolNames(rounds.get(1)).contains("ext_detail"),
                "search 命中后下轮 tools[] 实变（逐轮展开）");
        assertTrue(toolNames(rounds.get(2)).contains("ext_detail"), "展开驻留至终答轮");
        assertEquals("ext_detail | {\"type\":\"object\",\"description\":\"查询用例步骤明细\"}",
                result.messages().get(3).content(), "tool 消息回传 name + 完整 schema");
        assertEquals(2, result.rounds());
        assertEquals(2, result.dispatched(), "search 命中与展开工具执行各记一次");
        assertEquals(0, result.rejected());
        assertEquals("明细已取回", result.result().content());
    }

    /** ② Unknown query: honest empty answer (the search ran), resident list untouched. */
    @Test
    void unknownQueryAnswersEmptyWithoutTouchingResidentList() {
        stubCatalogKeys();
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(11, "kb_http", "{\"type\":\"object\"}"),
                item(12, "ext_detail", "{\"type\":\"object\"}")));
        scriptChain();
        script.add(toolCall(new LlmToolCall("c1", FcToolCatalog.NAME, "{\"query\":\"不存在\"}")));
        script.add(finalAnswer("没有该能力"));

        var result = runner().run(baseRequest(), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        List<ChatCompletionRequest> rounds = capturedRounds();
        assertEquals(2, rounds.size());
        assertEquals(toolNames(rounds.get(0)), toolNames(rounds.get(1)),
                "未命中=零展开，下轮清单不变");
        assertTrue(result.messages().get(3).content().contains("没有匹配"), "人话空账进 tool 消息");
        assertTrue(result.messages().get(3).content().contains("apiAccess.catalog"),
                "空账必带下一步指路（通用接口目录门）");
        assertEquals(0, result.rejected(), "零命中=检索正常完成，不记拒");
        assertEquals(1, result.dispatched(), "检索自身记一次派发");
    }

    /** ③ Expansion consumes the deferred pool: second same search honestly misses. */
    @Test
    void expandedToolLeavesDeferredPoolForNextSearch() {
        stubCatalogKeys();
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(11, "kb_http", "{\"type\":\"object\"}"),
                item(12, "ext_detail", "{\"type\":\"object\"}")));
        scriptChain();
        script.add(toolCall(new LlmToolCall("c1", FcToolCatalog.NAME, "{\"query\":\"ext\"}")));
        script.add(toolCall(new LlmToolCall("c2", FcToolCatalog.NAME, "{\"query\":\"ext\"}")));
        script.add(finalAnswer("已常驻无需再检索"));

        var result = runner().run(baseRequest(), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertTrue(result.messages().get(3).content().contains("ext_detail"), "首次命中展开");
        assertTrue(result.messages().get(5).content().contains("没有匹配"),
                "已展开=池已消耗，第二次检索诚实落空（正常完成非拒）");
        assertEquals(2, result.dispatched(), "两次检索各自正常完成=两次派发");
        assertEquals(0, result.rejected(), "落空不是拒绝");
    }

    /** ④ Legacy wiring (no catalog bean): a hallucinated search_tools stays a
     *    dispatcher refuse — fail-soft, the loop never throws. */
    @Test
    void hallucinatedSearchWithoutCatalogRefusesViaDispatcher() {
        when(gateway.getProjectFcAssembly(PROJECT_ID, ACTOR_ID)).thenReturn(List.of(
                item(11, "kb_http", "{\"type\":\"object\"}")));
        scriptChain();
        script.add(toolCall(new LlmToolCall("c1", FcToolCatalog.NAME, "{\"query\":\"x\"}")));
        script.add(finalAnswer("换个思路"));

        ReActRunner legacy = new ReActRunner(chain, new LlmFunctionCalling(gateway, MAPPER),
                new ToolDispatcher(gateway, MAPPER), new FcRoundPolicy(gateway),
                new AggregateQueryTool(gateway, MAPPER), LoopContextFitting.NONE, null);
        var result = legacy.run(baseRequest(), PROJECT_ID, null, ACTOR_ID, TASK_ID, null);

        assertEquals(1, result.rejected(), "清单外语义=拒（越权面零执行）");
        assertTrue(result.messages().get(3).content().contains("不在项目启用清单"));
        assertEquals("换个思路", result.result().content());
    }
}
