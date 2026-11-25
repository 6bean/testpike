package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockAiJobInfoRemote;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGateway;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockGenMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 contract tests for the bulk contract-enrichment execution leg of the
 * mock drafting consumer (AI data enrichment point). Four pinned contracts:
 * 1) a spec-enrich job runs its OWN leg — the default happy-path drafting
 *    instruction never reaches the model and the pinned contract endpoints
 *    (not the free prompt alone) drive the batch payload;
 * 2) the business-step frame stream lands on the process ledger in order
 *    under kind "mock-spec-enrich": queued, contract parsed, endpoint
 *    batching, deterministic floor done, AI batch i/N, advice backfill,
 *    draft ready — failure records an error frame;
 * 3) one LLM chat per endpoint batch (calls = ceil(endpoints / batch size))
 *    and the uploaded bundle carries BOTH forms: contract-direct rows and
 *    AI-enriched rows;
 * 4) any mid-chain exception seals the job failed through the same tail as
 *    every other leg (error frame + markMockFailed, zero preview write).
 */
class MockSpecEnrichDraftExecTest {

    private static final long JOB_ID = 88L;
    private static final long SPACE_ID = 12L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;
    private static final long SPEC_ID = 41L;
    private static final String KIND = "mock-spec-enrich";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final MockDraftArtifactGateway artifactGateway = mock(MockDraftArtifactGateway.class);
    private final TaskProcessEventStore processEventStore = mock(TaskProcessEventStore.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MockDraftGenServiceImpl service = new MockDraftGenServiceImpl(
            coreGateway, llmChain, llmRouter, artifactGateway, mapper, processEventStore);

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private MockGenMessage message() {
        return new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "spec-enrich", OPERATOR_ID, "pub-1");
    }

    private MockAiJobInfoRemote specEnrichJob(long specId) {
        try {
            String inputJson = mapper.writeValueAsString(
                    Map.of("prompt", "完善订单服务桩数据", "jobType", "spec-enrich", "specId", specId));
            return new MockAiJobInfoRemote(JOB_ID, SPACE_ID, "spec-enrich", "queued",
                    inputJson, "{}", null, null);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private CoreGateway.ApiEndpointBrief ep(String method, String path) {
        return new CoreGateway.ApiEndpointBrief(method, path,
                List.of(new CoreGateway.ApiParamBrief("id")),
                Map.of("200", "{\"orderId\":\"string\"}"));
    }

    private void stubContract(int endpointCount, String batchConfigValue) {
        List<CoreGateway.ApiEndpointBrief> eps = new java.util.ArrayList<>();
        for (int i = 0; i < endpointCount; i++) {
            eps.add(ep(i % 2 == 0 ? "POST" : "GET", "/api/orders/" + i));
        }
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(specEnrichJob(SPEC_ID));
        when(coreGateway.getApiSpec(SPEC_ID))
                .thenReturn(new CoreGateway.ApiSpecBrief(SPEC_ID, "订单服务契约", 3, eps));
        when(coreGateway.listSpecDraftAdvice(SPEC_ID, SPACE_ID)).thenReturn(directRows(endpointCount));
        when(coreGateway.getSysConfig(MockDraftGenServiceImpl.KEY_ENRICH_BATCH_SIZE))
                .thenReturn(batchConfigValue);
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(BATCH_ANSWER, "openai", "routed-model", 400, 200, 30L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202611/bundle-draft.json");
    }

    private List<CoreGateway.SpecDraftAdviceRow> directRows(int count) {
        List<CoreGateway.SpecDraftAdviceRow> rows = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            rows.add(new CoreGateway.SpecDraftAdviceRow("POST /api/orders/" + i,
                    "订单接口" + i, "POST", "exact", "/api/orders/" + i, "[]",
                    "{\"status\":200,\"body\":{\"orderId\":\"A" + i + "\"}}",
                    "openapi", "spec:" + SPEC_ID, "recommend"));
        }
        return rows;
    }

    /** Model answer: one enriched item + one fault item + one verdict row. */
    private static final String BATCH_ANSWER = """
            {"items":[
              {"name":"订单完善成功桩","method":"POST","path":"/api/orders/0","pathMatchType":"exact",
               "responseJson":"{\\"status\\":200,\\"body\\":{\\"orderId\\":\\"SO20261125001\\"}}"},
              {"name":"订单参数错误桩","method":"POST","path":"/api/orders/0","pathMatchType":"exact",
               "responseJson":"{\\"status\\":400,\\"body\\":{\\"code\\":\\"PARAM_INVALID\\"}}","fault":{"type":"delay"}}
            ],
            "verdicts":[{"endpointKey":"POST /api/orders/0","verdict":"recommend",
              "reason":"订单主链路，样例贴近真实业务", "conflictAction":"skip",
              "realism":86, "confidence":91}]}""";

    /** Contract 1 + 2: dedicated leg, default drafting text absent, ordered frames. */
    @Test
    void specEnrichRunsDedicatedLegAndRecordsOrderedBusinessFrames() {
        stubContract(3, null);

        service.generate(message());

        ArgumentCaptor<ChatCompletionRequest> chatCap =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain, times(1)).chat(chatCap.capture(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        String system = chatCap.getValue().messages().get(0).content();
        String user = chatCap.getValue().messages().get(1).content();
        assertFalse(system.contains("按需求描述逐条起草 happy-path 桩规则"),
                "spec-enrich must not ride the default drafting instruction");
        assertTrue(system.contains("AI 数据完善"), "point clause names the enrichment leg");
        assertTrue(user.contains("/api/orders/0") && user.contains("/api/orders/2"),
                "pinned contract endpoints drive the batch payload");

        InOrder frames = inOrder(processEventStore);
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("入队"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("契约解析"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("端点分1批"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("确定性直出完成"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("AI 数据增强 批 1/1"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("预裁回填"), isNull());
        frames.verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("step"), contains("草稿就绪"), isNull());
        frames.verifyNoMoreInteractions();
        verify(coreGateway).markMockPreview(eq(JOB_ID), eq("3/mock/202611/bundle-draft.json"));
    }

    /** Contract 3: one chat per batch (ceil(5/2)=3) and batch i/N frame per leg. */
    @Test
    void endpointBatchesDriveOneLlmCallEach() {
        stubContract(5, "2");

        service.generate(message());

        verify(llmChain, times(3)).chat(any(), eq(PROJECT_ID),
                any(LlmFallbackNotice.class), anyBoolean());
        verify(processEventStore).record(eq(KIND), anyString(), anyString(),
                eq("step"), contains("端点分3批"), isNull());
        verify(processEventStore).record(eq(KIND), anyString(), anyString(),
                eq("step"), contains("AI 数据增强 批 3/3"), isNull());
    }

    /** Contract 3 tail: bundle carries contract-direct rows AND AI-enriched rows. */
    @Test
    void uploadedBundleCarriesBothContractDirectAndAiEnrichedForms() throws Exception {
        stubContract(3, null);

        service.generate(message());

        ArgumentCaptor<byte[]> bundleCap = ArgumentCaptor.forClass(byte[].class);
        verify(artifactGateway).uploadDraftBundle(eq(JOB_ID), anyString(), bundleCap.capture());
        JsonNode bundle = mapper.readTree(new String(bundleCap.getValue(), StandardCharsets.UTF_8));
        assertEquals("spec-enrich", bundle.path("jobType").asText());
        JsonNode items = bundle.path("items");
        assertEquals(5, items.size(), "3 contract-direct rows from the floor door + 2 AI rows");
        int contractRows = 0;
        int aiRows = 0;
        for (JsonNode item : items) {
            if ("contract".equals(item.path("origin").asText())) {
                contractRows++;
                assertEquals("openapi", item.path("source").asText());
            } else if ("ai".equals(item.path("origin").asText())) {
                aiRows++;
            }
        }
        assertEquals(3, contractRows);
        assertEquals(2, aiRows);

        ArgumentCaptor<List<CoreGateway.SpecVerdictRow>> refineCap = ArgumentCaptor.forClass(List.class);
        verify(coreGateway).refineSpecVerdicts(eq(SPEC_ID), eq(SPACE_ID), refineCap.capture());
        assertEquals(1, refineCap.getValue().size());
        assertEquals("POST /api/orders/0", refineCap.getValue().get(0).endpointKey());
        assertEquals("recommend", refineCap.getValue().get(0).verdict());
    }

    /** Contract 4: door outage mid-leg = error frame, failure seal, zero preview. */
    @Test
    void midLegFailureRecordsErrorFrameAndSealsFailed() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(specEnrichJob(SPEC_ID));
        when(coreGateway.getApiSpec(SPEC_ID)).thenThrow(new ServiceException(
                "契约版本不存在或已被清理", BizErrorCode.VALIDATION_ERROR));

        service.generate(message());

        verify(processEventStore).record(eq(KIND), eq(String.valueOf(JOB_ID)),
                anyString(), eq("error"), anyString(), isNull());
        verify(coreGateway).markMockFailed(eq(JOB_ID), contains("契约版本不存在"));
        verify(artifactGateway, never()).uploadDraftBundle(anyLong(), anyString(), any(byte[].class));
        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
    }
}
