package com.sixbean.testpike.aiservice.doc.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmChatHandle;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmProperties;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmTimeoutResolver;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractDeadline;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Extraction-plane ceiling composition .
 * <p>Pinned here: every LLM call leaving the three extraction-plane sites
 * (final extraction round / group summarisation / gap ask) carries a clamp
 * that additionally caps the per-attempt timeout at the doc ceiling resolved
 * from ai.doc.llm-timeout-seconds — and only shortens, never lengthens. An
 * unset doc key keeps the clamp received from the deadline wiring
 * byte-identical (zero drift), so the override is opt-in by configuration.
 */
class DocExtractPlaneTimeoutClampTest {

    private static final long DOC_ID = 9L;
    private static final long PROJECT_ID = 4L;
    private static final String TOKEN = "plane-timeout-token-001";
    private static final String DOC_KEY = "ai.doc.llm-timeout-seconds";

    private final DocExtractTestFixtures fixtures = new DocExtractTestFixtures(DOC_ID, PROJECT_ID, TOKEN);
    private final CoreGateway coreGateway = fixtures.coreGateway();
    private final LlmFallbackChain llmChain = fixtures.llmChain();
    private final ProjectLlmRouter llmRouter = fixtures.llmRouter();
    private final LlmTimeoutResolver timeoutResolver = fixtures.timeoutResolver();
    private final DocExtractServiceImpl service = fixtures.pipeline(timeoutResolver);

    private void stubHappyChain() {
        fixtures.stubHappyChain();
    }

    /** Doc ceiling 45s → a 300s per-attempt timeout is shortened to 45s and
     *  a 30s timeout stays 30s (shorten-only composition, never lengthened). */
    @Test
    void docCeilingSet_clampShortensLongCallsOnly() {
        stubHappyChain();
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn("45");
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn(null);

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        UnaryOperator<Duration> clamp = fixtures.capturedClamp();
        assertEquals(Duration.ofSeconds(45), clamp.apply(Duration.ofSeconds(300)),
                "文档级上限必须钳短 300s 调用");
        assertEquals(Duration.ofSeconds(30), clamp.apply(Duration.ofSeconds(30)),
                "上限只缩短不放大");
    }

    /** Doc key unset → the composed clamp equals the deadline clamp face:
     *  a 300s call inside the default 7200s window stays byte-identical. */
    @Test
    void docKeyUnset_zeroDriftAgainstDeadlineClamp() {
        stubHappyChain();
        when(coreGateway.getSysConfig(DOC_KEY)).thenReturn(null);
        when(coreGateway.getSysConfig("llm.timeout-seconds")).thenReturn(null);
        when(coreGateway.getSysConfig(ExtractDeadline.CONFIG_KEY)).thenReturn(null);

        service.extract(new AiDocExtractMessage(DOC_ID, PROJECT_ID, 11L, null, TOKEN));

        UnaryOperator<Duration> clamp = fixtures.capturedClamp();
        assertEquals(Duration.ofSeconds(300), clamp.apply(Duration.ofSeconds(300)),
                "缺键（回退全局安装缺省 300s）时 300s 调用零改动");
        assertEquals(Duration.ofSeconds(120), clamp.apply(Duration.ofSeconds(120)),
                "缺键时短调用同样零改动");
    }
}
