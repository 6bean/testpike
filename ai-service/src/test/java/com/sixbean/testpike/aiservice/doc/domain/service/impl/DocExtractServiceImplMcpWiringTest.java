package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.domain.component.TokenCounter;
import com.sixbean.testpike.aiservice.mcp.domain.component.FakeMcpToolSession;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpKnowledgeBridge;
import com.sixbean.testpike.aiservice.mcp.domain.component.ToolInvocationRecorder;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpAssemblyRemoteServer;
import com.sixbean.testpike.aiservice.mcp.infrastructure.remote.McpInvocationRemoteInput;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class DocExtractServiceImplMcpWiringTest {

    private static final long DOC_ID = 5L;
    private static final long PROJECT_ID = 3L;
    private static final long SERVER_ID = 7L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final Map<Long, FakeMcpToolSession> fakes = new HashMap<>();

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "m", "k", "http://x", "config", null);
    }

    private McpKnowledgeBridge bridge() {
        when(coreGateway.getSysConfig(McpClientPool.CALL_BUDGET_KEY)).thenReturn(null);
        McpClientPool pool = new McpClientPool(coreGateway, new ToolInvocationRecorder(coreGateway),
                s -> {
                    FakeMcpToolSession f = new FakeMcpToolSession(s, null);
                    fakes.put(s.serverId(), f);
                    return f;
                });
        return new McpKnowledgeBridge(pool);
    }

    private DocExtractServiceImpl serviceWith(McpKnowledgeBridge bridge) {
        return new DocExtractServiceImpl(coreGateway, llmChain, llmRouter, new ObjectMapper(),
                new TokenCounter(), null, bridge);
    }

    
    private List<String> stubChunksAndLlm() {
        List<String> users = new ArrayList<>();
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "A", "content", "用户可登录"),
                Map.of("seq", 2, "chapter", "B", "content", "首页显示欢迎语")));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean(), any()))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    users.add(r.messages().get(1).content());
                    return new LlmChatResult(
                            "{\"requirements\":[{\"title\":\"登录\",\"acceptanceCriteria\":\"AC\","
                                    + "\"chapter\":\"A\",\"confidence\":0.9}]}",
                            "openai", "m", 10, 5, 20L);
                });
        return users;
    }

    private void stubAssemblyWithKbTool() {
        try {
            when(coreGateway.getMcpAssemblyServers(anyLong())).thenReturn(List.of(
                    new McpAssemblyRemoteServer(SERVER_ID, "kb-mcp",
                            "http://127.0.0.1:1/mcp", 2, null,
                            List.of("kb_search", "shell_exec"))));
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    
    @Test
    void F2提取注入知识段并留痕taskId空() {
        stubAssemblyWithKbTool();
        List<String> users = stubChunksAndLlm();

        serviceWith(bridge()).extract(
                new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));

        String user = users.get(0);
        assertTrue(user.contains("外部知识库工具检索结果"), "知识段未进 F2 user prompt");
        assertTrue(user.contains("结果 of kb_search"), "工具结果正文缺失");
        FakeMcpToolSession session = fakes.get(SERVER_ID);
        assertEquals(List.of("kb_search|用户可登录"), session.calls(),
                "检索入参=首块正文（危险命名 shell_exec 不得被调用）");

        ArgumentCaptor<List<McpInvocationRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(coreGateway, timeout(3000)).recordToolInvocations(cap.capture());
        McpInvocationRemoteInput trace = cap.getValue().get(0);
        assertNull(trace.taskId(), "F2 无任务语境留痕 task_id=null（V42 裁定⑦可空面）");
        assertEquals(SERVER_ID, trace.serverId());
        assertEquals("kb_search", trace.toolName());
    }

 
    @Test
    void 旧构造零MCP注入零破坏() {
        stubAssemblyWithKbTool();
        List<String> users = stubChunksAndLlm();

        DocExtractServiceImpl legacy = new DocExtractServiceImpl(coreGateway, llmChain,
                llmRouter, new ObjectMapper(), new TokenCounter());
        legacy.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));

        assertTrue(!users.get(0).contains("外部知识库工具检索结果"),
                "bridge=null 不得引入注入段（基线零破坏）");
        verify(coreGateway, after(500).never()).recordToolInvocations(anyList());
    }

 
    @Test
    void 装配空集零注入提取不失败() {
        when(coreGateway.getMcpAssemblyServers(anyLong())).thenReturn(List.of());
        List<String> users = stubChunksAndLlm();

        serviceWith(bridge()).extract(
                new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));

        assertTrue(!users.get(0).contains("外部知识库工具检索结果"), "空集不得出现注入段");
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), anyList(), eq("tok-1"));
        verify(coreGateway, after(500).never()).recordToolInvocations(anyList());
    }

    
    @Test
    void 装配取数异常不阻断提取() {
        when(coreGateway.getMcpAssemblyServers(anyLong()))
                .thenThrow(new IllegalStateException("core down"));
        List<String> users = stubChunksAndLlm();

        serviceWith(bridge()).extract(
                new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, "tok-1"));

        assertTrue(!users.get(0).contains("外部知识库工具检索结果"));
        verify(coreGateway).writeExtractionResult(eq(DOC_ID), anyList(), eq("tok-1"));
    }
}
