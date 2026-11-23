package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.common.util.LlmExtractMarkers;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;


class UsageCalibrationRecorderTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final UsageCalibrationRecorder recorder =
            new UsageCalibrationRecorder(gateway, new TokenCounter());

    private LlmChatResult result(int promptTokens) {
        return new LlmChatResult("ok", "dashscope", "qwen-plus", promptTokens, 5, 120L);
    }

    private LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("dashscope", "qwen-plus", "k", "http://x", "config",
                1_048_576, 7L);
    }

    
    @Test
    void extractStageSamplesAllFields() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus", List.of(
                new ChatMessage("system", LlmExtractMarkers.DOC_EXTRACT_REQUIREMENTS + " 抽取需求点"),
                new ChatMessage("user", "正文内容")), null, null);
        recorder.record(req, result(42), endpoint(), 3L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        CalibrationSample s = cap.getValue();
        assertEquals(CalibrationSample.CHAIN_EXTRACT, s.chain());
        assertEquals(42, s.usagePromptTokens());
        assertEquals(7L, s.llmConfigId());
        assertEquals(3L, s.projectId());
        assertEquals("qwen-plus", s.model());
        assertEquals("dashscope", s.provider());
        assertEquals(120L, s.latencyMs());
        TokenEstimator routed = TokenEstimatorRegistry.resolve(
                endpoint().tokenizerFamily(), endpoint().modelName());
        assertEquals(routed.countTexts(req.messages().stream()
                .map(ChatMessage::content).toList()), s.estTokens());
        assertEquals(routed.engineId(), s.tokenizerEngine());
    }

    
    @Test
    void summaryStageClassifiedByMarker() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus", List.of(
                new ChatMessage("system", LlmExtractMarkers.DOC_EXTRACT_SUMMARY + " 归并摘要"),
                new ChatMessage("user", "多组结果")), null, null);
        recorder.record(req, result(30), endpoint(), 3L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        assertEquals(CalibrationSample.CHAIN_SUMMARY, cap.getValue().chain());
    }

    
    @Test
    void nullEndpointStillSamples() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus",
                List.of(new ChatMessage("user", "hi")), null, null);
        recorder.record(req, result(1), null, 9L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        assertNull(cap.getValue().llmConfigId());
        assertEquals(9L, cap.getValue().projectId());
    }

    /** AP-4 prompt tokens served from the provider prefix cache ride
 * the sample row — the E9 calibration page reads the cache_hint effect from
 * this column, so the benefit is measured, not promised. */
    @Test
    void cachedPromptTokensRideTheSample() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus",
                List.of(new ChatMessage("user", "hi")), null, null);
        recorder.record(req, new LlmChatResult("ok", "dashscope", "qwen-plus",
                42, 5, 120L, null, 768), endpoint(), 3L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        assertEquals(768, cap.getValue().cachedTokens());
    }

    /** Provider reported no cache block → the sample carries an honest 0
     * (missing usage detail never becomes a fabricated non-zero). */
    @Test
    void missingCacheBlockSamplesHonestZero() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus",
                List.of(new ChatMessage("user", "hi")), null, null);
        recorder.record(req, result(42), endpoint(), 3L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        assertEquals(0, cap.getValue().cachedTokens());
    }

    /** Prefix economics is run as a managed target: every sampled call feeds
     * a rolling cached/prompt token ledger so the hit ratio is readable
     * without digging through per-row samples. */
    @Test
    void prefixCacheRatioAggregatesAcrossCalls() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus",
                List.of(new ChatMessage("user", "hi")), null, null);
        recorder.record(req, new LlmChatResult("ok", "dashscope", "qwen-plus",
                1000, 5, 10L, null, 400), endpoint(), 3L);
        recorder.record(req, new LlmChatResult("ok", "dashscope", "qwen-plus",
                1000, 5, 10L, null, 100), endpoint(), 3L);
        assertEquals(2000L, recorder.reportedPromptTokens());
        assertEquals(500L, recorder.cachedPrefixTokens());
        assertEquals(0.25, recorder.prefixCacheHitRatio(), 1e-9);
    }

    /** Zero reported prompt tokens must not divide by zero. */
    @Test
    void prefixCacheRatioIsHonestZeroWithoutUsage() {
        assertEquals(0.0, recorder.prefixCacheHitRatio(), 1e-9);
    }

    
    @Test
    void zeroUsageDeviationIsNull() {
        ChatCompletionRequest req = new ChatCompletionRequest("qwen-plus",
                List.of(new ChatMessage("user", "hi")), null, null);
        recorder.record(req, result(0), endpoint(), 1L);
        ArgumentCaptor<CalibrationSample> cap = ArgumentCaptor.forClass(CalibrationSample.class);
        verify(gateway, timeout(3000)).recordLlmUsage(cap.capture());
        assertNull(cap.getValue().deviationRate());
    }
}
