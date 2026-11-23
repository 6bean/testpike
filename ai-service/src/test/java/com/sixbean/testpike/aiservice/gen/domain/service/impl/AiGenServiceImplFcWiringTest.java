package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.common.util.ActionJsonValidator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.domain.component.CaseJsonParser;
import com.sixbean.testpike.aiservice.gen.domain.component.CoverageChecker;
import com.sixbean.testpike.aiservice.gen.domain.component.PriorityRule;
import com.sixbean.testpike.aiservice.gen.domain.component.ScriptLimitResolver;
import com.sixbean.testpike.aiservice.gen.domain.component.SelfCheckValidator;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolInvokeResultRemote;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.support.GenTestFixtures;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpClientPool;
import com.sixbean.testpike.aiservice.mcp.domain.component.McpTaskToolBag;
import com.sixbean.testpike.aiservice.tool.domain.component.AggregateQueryTool;
import com.sixbean.testpike.aiservice.tool.domain.component.FcRoundPolicy;
import com.sixbean.testpike.aiservice.tool.domain.component.LlmFunctionCalling;
import com.sixbean.testpike.aiservice.tool.domain.component.ReActRunner;
import com.sixbean.testpike.aiservice.tool.domain.component.ToolDispatcher;
import com.sixbean.testpike.common.mq.AiGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AiGenServiceImplFcWiringTest {

    private static final long TASK_ID = 120L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long NEW_CASE_ID = 900L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final McpClientPool mcpPool = mock(McpClientPool.class);

    private static LlmEndpointConfig endpoint() {
        return GenTestFixtures.endpoint();
    }

    
    private static LlmEndpointConfig fallbackEndpoint() {
        return new LlmEndpointConfig("deepseek", "backup-model", "dummy-key",
                "http://localhost:4001/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    
    private AiGenServiceImpl service(ReActRunner reactRunner) {
        return new AiGenServiceImpl(llmChain, llmRouter, coreGateway, MAPPER,
                new CaseJsonParser(MAPPER), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway), null, null,
                mcpPool, reactRunner);
    }

    private ReActRunner reactRunner() {
        return new ReActRunner(llmChain, new LlmFunctionCalling(coreGateway, MAPPER),
                new ToolDispatcher(coreGateway, MAPPER), new FcRoundPolicy(coreGateway),
                new AggregateQueryTool(coreGateway, MAPPER));
    }

    private static ToolFcAssemblyRemoteItem httpTool(long id, String name) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, 1, "{\"type\":\"object\"}", 5000, false);
    }

    private static AiGenMessage message() {
        return new AiGenMessage(TASK_ID, PROJECT_ID, OPERATOR_ID, 1,
                "用户可以使用正确的账号密码登录系统", null, null);
    }

    
    private static String stage1Json() {
        return GenTestFixtures.stage1Json();
    }

    private static final String STAGE2_JSON = "{\"version\":\"v2\",\"caseId\":\"TC-FC-001\",\"endType\":\"web\","
            + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"{{ENV:URL}}/login\"}}],"
            + "\"steps\":[{\"action\":\"click\",\"target\":{\"css\":\"#login-btn\",\"chain\":[\"css\"]}},"
            + "{\"action\":\"expect\",\"target\":{\"text\":\"登录成功跳转首页\"}}],"
            + "\"env\":{\"ENV\":\"dev\",\"PACK\":\"p\",\"DATA\":\"d\"}}";

    private void stubGenBase() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getProject(PROJECT_ID)).thenReturn(
                new CoreGateway.ProjectBrief(PROJECT_ID, "fc", "zh-CN", 1));
        when(coreGateway.createCase(eq(OPERATOR_ID), any())).thenReturn(
                Map.of("id", NEW_CASE_ID, "caseNo", "TC-fc-900"));
    }

    
    private void stubChainByMarker() {
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON") ? stage1Json() : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });
    }

    private final List<ChatCompletionRequest> requests = new ArrayList<>();

    
    private void stubChainCapturing(LlmChatResult... fcEndpoints) {
        int[] endpoint = {0};
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    requests.add(r);
                    LlmChatResult scripted = endpoint[0] < fcEndpoints.length ? fcEndpoints[endpoint[0]] : null;
                    endpoint[0]++;
                    if (scripted != null) {
                        return new LlmChatHandle(scripted, endpoint());
                    }
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON") ? stage1Json() : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L),
                            endpoint());
                });
    }

    
    @Test
    void fc装配非空_stage1走react一轮工具闭环后落库() {
        stubGenBase();
        when(coreGateway.getProjectFcAssembly(PROJECT_ID, OPERATOR_ID))
                .thenReturn(List.of(httpTool(11, "kb_http")));
        when(coreGateway.invokeTool(eq(OPERATOR_ID), eq(TASK_ID), eq(11L), any(), isNull(), eq(PROJECT_ID)))
                .thenReturn(new ToolInvokeResultRemote(11L, "kb_http", true,
                        "{\"rows\":2}", null, null, null, 50L));
        stubChainCapturing(new LlmChatResult("", "openai", "routed-model", 10, 5, 1L,
                List.of(new LlmToolCall("c1", "kb_http", "{\"q\":\"登录\"}"))));

        service(reactRunner()).generate(message());

        ChatCompletionRequest first = requests.get(0);
        assertEquals(1, first.tools().size(), "stage1 首位候选端点必须挂装配 tools");
        assertTrue(first.messages().get(0).content().contains("FC_TOOL_CALL"), "契约位注入");
        ChatCompletionRequest second = requests.get(1);
        assertTrue(second.messages().stream().anyMatch(m -> "tool".equals(m.role())),
                "工具结果回填后进第链路二对话");
        verify(coreGateway).invokeTool(eq(OPERATOR_ID), eq(TASK_ID), eq(11L),
                eq("{\"q\":\"登录\"}"), isNull(), eq(PROJECT_ID));
        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }

    
    @Test
    void fc装配空_直通形状零漂移主链照常() {
        stubGenBase();
        when(coreGateway.getProjectFcAssembly(PROJECT_ID, OPERATOR_ID)).thenReturn(List.of());
        stubChainCapturing();

        service(reactRunner()).generate(message());

        assertNull(requests.get(0).tools(), "空计划禁挂 tools（普通补全零漂移）");
        assertTrue(requests.get(0).messages().get(0).content().contains("STAGE1_CASE_JSON"));
        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }

    
    @Test
    void 旧装配无react_runner_零接线零装配回查() {
        stubGenBase();
        stubChainByMarker();
        AiGenServiceImpl legacy = new AiGenServiceImpl(llmChain, llmRouter, coreGateway, MAPPER,
                new CaseJsonParser(MAPPER), new SelfCheckValidator(),
                new CoverageChecker(), new PriorityRule(), new ActionJsonValidator(),
                new ScriptLimitResolver(coreGateway), null, null);

        legacy.generate(message());

        verify(coreGateway, never()).getProjectFcAssembly(anyLong(), any());
        verify(coreGateway).markDone(TASK_ID, NEW_CASE_ID);
    }

    
    @Test
    void mcp袋任务级开合一次() {
        stubGenBase();
        when(coreGateway.getProjectFcAssembly(PROJECT_ID, OPERATOR_ID)).thenReturn(List.of());
        stubChainByMarker();
        McpTaskToolBag bag = mock(McpTaskToolBag.class);
        when(mcpPool.openTask(TASK_ID, PROJECT_ID)).thenReturn(bag);

        service(reactRunner()).generate(message());

        verify(mcpPool, times(1)).openTask(TASK_ID, PROJECT_ID);
        verify(mcpPool, times(1)).finish(bag);
    }

    
    @Test
    void react降级生效端点_回填不失真() {
        stubGenBase();
        when(coreGateway.getProjectFcAssembly(PROJECT_ID, OPERATOR_ID)).thenReturn(List.of());
        when(llmChain.chatHandle(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class)))
                .thenAnswer(inv -> {
                    ChatCompletionRequest r = inv.getArgument(0);
                    String system = r.messages().get(0).content();
                    String content = system.contains("STAGE1_CASE_JSON") ? stage1Json() : STAGE2_JSON;
                    return new LlmChatHandle(
                            new LlmChatResult(content, "deepseek", "backup-model", 90, 40, 30L),
                            fallbackEndpoint());
                });

        service(reactRunner()).generate(message());

        verify(coreGateway).markModel(TASK_ID, "backup-model");
    }
}
