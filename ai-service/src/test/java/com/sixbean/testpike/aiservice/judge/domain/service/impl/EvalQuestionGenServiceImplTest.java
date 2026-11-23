package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenPreviewView;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class EvalQuestionGenServiceImplTest {

    private static final long DOC_ID = 21L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), null, null);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static QaGenRequest request(String modelHint) {
        return new QaGenRequest(PROJECT_ID, DOC_ID, modelHint, OPERATOR_ID);
    }

    private static Map<String, Object> chunk(int seq, String content) {
        return Map.of("seq", seq, "chapter", "验收标准", "content", content);
    }

    private void stubChunks(List<Map<String, Object>> chunks) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(chunks);
    }

    private void stubLlmContent(String content) {
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(content, "openai", "routed-model", 100, 50, 20L));
    }

    private static final String VALID_LLM_JSON = """
            {"items":[{"question":"订单创建接口用什么方法？","answer":"POST /api/v1/orders",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    
    @Test
    void generate_单块成功_返回预览清单与实耗成本() {
        stubChunks(List.of(chunk(1, "创建订单接口 POST /api/v1/orders。")));
        stubLlmContent(VALID_LLM_JSON);

        QaGenPreviewView view = service.generate(request(null));

        assertEquals(1, view.items().size());
        assertEquals("订单创建接口用什么方法？", view.items().get(0).get("question"));
        assertEquals("创建订单接口 POST /api/v1/orders", view.items().get(0).get("sourceAnchor"));
        assertEquals(1, view.chunkCount());
        assertEquals(100, view.promptTokens());
        assertEquals(50, view.completionTokens());
        assertEquals("routed-model", view.model());

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String system = prompt.getValue().messages().get(0).textContent();
        assertTrue(system.contains(LlmExtractMarkers.JUDGE_QA_GEN), "缺契约位");
        assertTrue(system.contains("sourceAnchor"), "缺出处原句约束");
        assertTrue(system.contains("禁发明"), "缺防发明红线");
    }

    
    @Test
    void generate_空分块_零LLM调用人话失败() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of());

        assertThrows(ServiceException.class, () -> service.generate(request(null)));
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
    }

    
    @Test
    void generate_块数超成本闸_K8现读人话拒零LLM() {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(
                List.of(chunk(1, "甲段"), chunk(2, "乙段")));
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_MAX_CHUNKS))
                .thenReturn("1");
        // : the cost-gate rejection face lives behind the closed door.
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");

        ServiceException e = assertThrows(ServiceException.class,
                () -> service.generate(request(null)));
        assertTrue(e.getMessage().contains("1"), "人话原因应含当前上限值");
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
    }

    
    @Test
    void generate_非法输出_人话失败() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent("对不起，我无法生成问答对。");

        assertThrows(ServiceException.class, () -> service.generate(request(null)));
    }

    
    @Test
    void generate_条目缺question_人话失败() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent("""
                {"items":[{"answer":"POST /api/v1/orders"}]}""");

        assertThrows(ServiceException.class, () -> service.generate(request(null)));
    }

    
    @Test
    void generate_全块无出题点_聚合空人话失败() {
        stubChunks(List.of(chunk(1, "寒暄段落")));
        stubLlmContent("{\"items\":[]}");

        assertThrows(ServiceException.class, () -> service.generate(request(null)));
    }

    
    @Test
    void generate_modelHint非空_钉模型走pinned语义() {
        stubChunks(List.of(chunk(1, "任意正文")));
        stubLlmContent(VALID_LLM_JSON);

        service.generate(request("glm-4-plus"));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        ArgumentCaptor<Boolean> pinned = ArgumentCaptor.forClass(Boolean.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                pinned.capture());
        assertEquals("glm-4-plus", prompt.getValue().model());
        assertTrue(pinned.getValue());
    }

    
    @Test
    void generate_多块聚合_条目合并token累加() {
        // : this pins the legacy per-chunk accumulation face; the
        // grouped door merges cross-group duplicates, asserted in
        // EvalQuestionGenGroupedIntakeTest instead.
        when(coreGateway.getSysConfig(EvalQuestionGenServiceImpl.KEY_GROUPED_INTAKE_ENABLED))
                .thenReturn("false");
        stubChunks(List.of(chunk(1, "第一段"), chunk(2, "第二段")));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model", 100, 50, 20L))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model", 80, 30, 15L));

        QaGenPreviewView view = service.generate(request(null));

        assertEquals(2, view.items().size());
        assertEquals(2, view.chunkCount());
        assertEquals(180, view.promptTokens());
        assertEquals(80, view.completionTokens());
    }
}
