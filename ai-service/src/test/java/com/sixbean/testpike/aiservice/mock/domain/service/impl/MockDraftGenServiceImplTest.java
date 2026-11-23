package com.sixbean.testpike.aiservice.mock.domain.service.impl;

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
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockGenMessage;
import com.sixbean.testpike.common.util.LlmExtractMarkers;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L2 orchestration tests for the AI mock-drafting consumer service (
 * . Infrastructure seams (CoreGateway HTTP door, LLM chain, artifact
 * gateway) are mocked — real-chain quality belongs to L3/L4. The contract
 * pinned here is the orchestration order and its failure semantics:
 * pull job row → skip non-queued → markRunning → LLM (system carries the
 * MOCK_DRAFT_GEN_JSON contract marker) → parse → upload bundle → preview
 * write-back; a lost running race acks silently; any other failure seals
 * the job failed in plain words instead of leaving it stuck in running.
 */
class MockDraftGenServiceImplTest {

    private static final long JOB_ID = 77L;
    private static final long SPACE_ID = 9L;
    private static final long PROJECT_ID = 3L;
    private static final long OPERATOR_ID = 11L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final MockDraftArtifactGateway artifactGateway = mock(MockDraftArtifactGateway.class);
    private final MockDraftGenServiceImpl service = new MockDraftGenServiceImpl(
            coreGateway, llmChain, llmRouter, artifactGateway, new ObjectMapper());

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static MockGenMessage message() {
        return new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "nl-stub", OPERATOR_ID, "pub-token-1");
    }

    private static MockAiJobInfoRemote job(String status, String prompt) {
        String inputJson;
        try {
            inputJson = new ObjectMapper().writeValueAsString(
                    Map.of("prompt", prompt, "jobType", "nl-stub"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new MockAiJobInfoRemote(JOB_ID, SPACE_ID, "nl-stub", status, inputJson,
                "{}", null, null);
    }

    private static final String VALID_LLM_JSON = """
            {"items":[{"name":"下单成功桩","method":"POST","path":"/api/orders",
            "pathMatchType":"exact","responseJson":"{\\"status\\":200}"}]}""";

    private void stubHappyPath(String llmContent) {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "为订单服务生成桩"));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(llmContent, "openai", "routed-model", 100, 50, 20L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202610/abcd1234-draft.json");
    }

    /** Drafting job row for any application point edge-scenarios / spec-sync). */
    private static MockAiJobInfoRemote job(String status, String prompt, String jobType) {
        String inputJson;
        try {
            inputJson = new ObjectMapper().writeValueAsString(
                    Map.of("prompt", prompt, "jobType", jobType));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new MockAiJobInfoRemote(JOB_ID, SPACE_ID, jobType, status, inputJson,
                "{}", null, null);
    }

    /**
 * Edge-scenarios job : the system prompt must name the application
 * point AND carry the fault-playbook instruction (the full status/delay/
 * disconnect spectrum), so the drafting model produces a fault pack instead
 * of plain happy-path stubs.
 */
    @Test
    void edgeScenariosJob_systemPromptCarriesFaultPlaybookInstruction() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "订单查询接口", "edge-scenarios"));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model", 100, 50, 20L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202610/x-draft.json");

        service.generate(new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "edge-scenarios",
                OPERATOR_ID, "pub-token-1"));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String system = prompt.getValue().messages().get(0).content();
        assertTrue(system.contains("edge-scenarios"), "system prompt must name the application point");
        assertTrue(system.contains("故障剧本"),
                "edge-scenarios prompt must instruct the fault playbook, got: " + system);
    }

    /** Spec-sync job : the system prompt carries the contract-sync instruction. */
    @Test
    void specSyncJob_systemPromptCarriesContractSyncInstruction() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "订单创建契约", "spec-sync"));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model", 100, 50, 20L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202610/x-draft.json");

        service.generate(new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "spec-sync",
                OPERATOR_ID, "pub-token-1"));

        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                anyBoolean());
        String system = prompt.getValue().messages().get(0).content();
        assertTrue(system.contains("spec-sync"), "system prompt must name the application point");
        assertTrue(system.contains("契约同步"),
                "spec-sync prompt must instruct contract sync, got: " + system);
    }

    /** Main chain: running seal → marker-carrying prompt → bundle upload → preview ref = objectKey. */
    @Test
    void queuedJob_runsFullChainAndPreviewsWithUploadedObjectKey() {
        stubHappyPath(VALID_LLM_JSON);

        service.generate(message());

        verify(coreGateway).markMockRunning(JOB_ID);
        ArgumentCaptor<ChatCompletionRequest> prompt =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(prompt.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class),
                eq(false));
        String system = prompt.getValue().messages().get(0).content();
        assertTrue(system.contains(LlmExtractMarkers.MOCK_DRAFT_GEN),
                "system prompt must carry the drafting contract marker");
        ArgumentCaptor<byte[]> bundle = ArgumentCaptor.forClass(byte[].class);
        verify(artifactGateway).uploadDraftBundle(eq(JOB_ID), eq("draft.json"),
                bundle.capture());
        String uploaded = new String(bundle.getValue(), StandardCharsets.UTF_8);
        assertTrue(uploaded.contains("/api/orders"), "bundle must carry the parsed draft items");
        verify(coreGateway).markMockPreview(JOB_ID, "3/mock/202610/abcd1234-draft.json");
        verify(coreGateway, never()).markMockFailed(anyLong(), anyString());
    }

    /** A job already moved out of queued (cancelled/consumed) is skipped with zero side effects. */
    @Test
    void nonQueuedJob_isSkippedWithoutRunningSealOrLlm() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("cancelled", "为订单服务生成桩"));

        assertDoesNotThrow(() -> service.generate(message()));

        verify(coreGateway, never()).markMockRunning(anyLong());
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(coreGateway, never()).markMockFailed(anyLong(), anyString());
    }

    /** Ghost job row (deleted after publish) is skipped silently — nothing to seal. */
    @Test
    void missingJobRow_isSkippedSilently() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(null);

        assertDoesNotThrow(() -> service.generate(message()));

        verify(coreGateway, never()).markMockRunning(anyLong());
        verify(coreGateway, never()).markMockFailed(anyLong(), anyString());
    }

    /** Losing the queued→running race (concurrent redelivery) acks silently, no failure seal. */
    @Test
    void lostRunningRace_acksSilentlyWithoutLlmOrFailureSeal() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "为订单服务生成桩"));
        org.mockito.Mockito.doThrow(new ServiceException(
                "任务已处于运行态，重复回写被拒", BizErrorCode.VALIDATION_ERROR))
                .when(coreGateway).markMockRunning(JOB_ID);

        assertDoesNotThrow(() -> service.generate(message()));

        verify(llmRouter, never()).route(anyLong());
        verify(coreGateway, never()).markMockFailed(anyLong(), anyString());
        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
    }

    /** Model output without draft items seals the job failed in plain words (no silent preview). */
    @Test
    void emptyItems_sealsFailedWithoutPreview() {
        stubHappyPath("{\"items\":[]}");

        assertDoesNotThrow(() -> service.generate(message()));

        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markMockFailed(eq(JOB_ID), reason.capture());
        assertTrue(reason.getValue().contains("桩条目"),
                "failure reason must be a plain-word sentence, got: " + reason.getValue());
    }

    /** Any mid-chain exception (LLM down, upload refused) is sealed failed, never left running. */
    @Test
    void chainException_sealsFailedAndDoesNotRethrow() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "为订单服务生成桩"));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenThrow(new IllegalStateException("provider unreachable"));

        assertDoesNotThrow(() -> service.generate(message()));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markMockFailed(eq(JOB_ID), reason.capture());
        assertTrue(reason.getValue().contains("provider unreachable"));
        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
    }

    /** A blank prompt (stored input without text) is refused before any token is burned. */
    @Test
    void blankPrompt_failsBeforeLlmCall() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "   "));

        assertDoesNotThrow(() -> service.generate(message()));

        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(coreGateway).markMockFailed(eq(JOB_ID), anyString());
    }

    /**
 * Triage : an llm-fallback job pulled by the DRAFTING
 * consumer must never ride the drafting chain. The queue payload carries
 * no cache identity (space/path/fingerprint live only in the synchronous
 * miss-time leg), so drafting it would burn tokens on an answer nobody
 * can case. Contract: the job seals running→failed in plain words naming
 * the sync leg, with zero router lookups, zero chats, zero uploads.
 */
    @Test
    void llmFallbackType_refusedAsDraftBeforeAnyTokenBurn() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job("queued", "为长尾端点即时代答", "llm-fallback"));

        assertDoesNotThrow(() -> service.generate(
                new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "llm-fallback", OPERATOR_ID, "t-1")));

        InOrder order = org.mockito.Mockito.inOrder(coreGateway);
        order.verify(coreGateway).markMockRunning(JOB_ID);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        order.verify(coreGateway).markMockFailed(eq(JOB_ID), reason.capture());
        assertTrue(reason.getValue().contains("llm-fallback"),
                "refusal must name the job type, got: " + reason.getValue());
        assertTrue(reason.getValue().contains("同步"),
                "refusal must point at the sync generation leg, got: " + reason.getValue());
        verify(llmRouter, never()).route(anyLong());
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(artifactGateway, never()).uploadDraftBundle(anyLong(), anyString(), any());
        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
    }

    // ---- A-4 record-summarize induction leg ----

    private static MockAiJobInfoRemote summarizeJob(Long recordingId) {
        String inputJson;
        try {
            java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("prompt", "归纳录制流量");
            body.put("jobType", "record-summarize");
            if (recordingId != null) {
                body.put("recordingId", recordingId);
            }
            inputJson = new ObjectMapper().writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new MockAiJobInfoRemote(JOB_ID, SPACE_ID, "record-summarize", "queued",
                inputJson, "{}", null, null);
    }

    private static com.sixbean.testpike.common.mq.MockRecordingFrameMessage recordedFrame(
            String path, String responseBody) {
        return new com.sixbean.testpike.common.mq.MockRecordingFrameMessage("f-1", "GET", path,
                java.util.Map.of(), null, 200, java.util.Map.of("content-type", "application/json"),
                responseBody, 10, 1700000000000L, "space.key", 501L);
    }

    /**
 * Summarize leg : a bound sealed box pulls its masked frame
 * sample over the core door, one induction chat carries the frames into
 * the user prompt (never raw originals — capture side masked them) plus a
 * rides the SAME draft-bundle tail (upload + preview write-back) so the
 * confirmation drawer stays single-sourced.
 */
    @Test
    void recordSummarizeType_inductsPreviewFromMaskedFrameSample() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(summarizeJob(501L));
        when(coreGateway.getMockRecordingFrames(501L)).thenReturn(java.util.List.of(
                recordedFrame("/orders/1", "{\"id\":1}"),
                recordedFrame("/orders/2", "{\"id\":2}")));
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model", 100, 50, 20L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202610/sum-1.json");

        assertDoesNotThrow(() -> service.generate(
                new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "record-summarize", OPERATOR_ID, "t-4")));

        ArgumentCaptor<ChatCompletionRequest> req = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmChain).chat(req.capture(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean());
        String system = req.getValue().messages().get(0).content();
        String user = req.getValue().messages().get(1).content();
        assertTrue(system.contains("record-summarize"),
                "system prompt must name the application point, got: " + system);
        assertTrue(system.contains("待人工确认"),
                "system prompt must pin the suspicion marker for dubious items, got: " + system);
        assertTrue(user.contains("/orders/1") && user.contains("/orders/2"),
                "user prompt must carry the recorded frame sample, got: " + user);
        verify(artifactGateway).uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class));
        verify(coreGateway).markMockPreview(JOB_ID, "3/mock/202610/sum-1.json");
        verify(coreGateway, never()).markMockFailed(anyLong(), anyString());
    }

    /**
     * Summarize leg, empty cassette: induction over silence would burn tokens
     * to summarize nothing — the row seals failed in plain words BEFORE any
     * router lookup or chat (zero token burn contract).
     */
    @Test
    void recordSummarizeType_emptyCassetteSealsFailedBeforeAnyTokenBurn() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(summarizeJob(501L));
        when(coreGateway.getMockRecordingFrames(501L)).thenReturn(java.util.List.of());

        assertDoesNotThrow(() -> service.generate(
                new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "record-summarize", OPERATOR_ID, "t-5")));

        verify(coreGateway).markMockRunning(JOB_ID);
        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markMockFailed(eq(JOB_ID), reason.capture());
        assertTrue(reason.getValue().contains("没录到"),
                "failure must state the empty cassette in plain words, got: " + reason.getValue());
        verify(llmRouter, never()).route(anyLong());
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
        verify(artifactGateway, never()).uploadDraftBundle(anyLong(), anyString(), any());
        verify(coreGateway, never()).markMockPreview(anyLong(), anyString());
    }

    /**
     * Summarize leg, unbound job (recordingId absent from input_json — core
     * gates this at create, so reaching the consumer means a corrupt/raced
     * row): honest failure seal, the frame door is never knocked.
     */
    @Test
    void recordSummarizeType_missingCassetteBindingSealsFailedWithoutPullingFrames() {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(summarizeJob(null));

        assertDoesNotThrow(() -> service.generate(
                new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "record-summarize", OPERATOR_ID, "t-6")));

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markMockFailed(eq(JOB_ID), reason.capture());
        assertTrue(reason.getValue().contains("录制盒"),
                "failure must name the missing cassette binding, got: " + reason.getValue());
        verify(coreGateway, never()).getMockRecordingFrames(anyLong());
        verify(llmChain, never()).chat(any(), anyLong(), any(), anyBoolean());
    }
}
