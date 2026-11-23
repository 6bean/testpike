package com.sixbean.testpike.aiservice.mock.domain.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockDraftArtifactGateway;
import com.sixbean.testpike.aiservice.mock.infrastructure.remote.MockAiJobInfoRemote;
import com.sixbean.testpike.common.mq.MockGenMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EF batch piece 2: the mock-drafting door consumes the single-source
 * RecallDensityGate verdict at its shared preview write-back tail (both the
 * requirement-drafting leg and the record-induction leg), naming the
 * numbers on one WARN line. This plane has no planner face, so the
 * denominator rides the gate's char→estimated-token helper (chars x 0.75),
 * the same estimator every calibration booked against. Per-domain hot key
 * ai.mock.recall-density-floor-per-ktoken, factory 20.3 (V501 measurement x
 * 0.6); a missing row falls back to that default, an explicit 0 switches
 * the check off.
 * <p>L2 posture: infrastructure mocks only, same face as
 * MockDraftGenServiceImplTest.
 */
class MockDraftRecallDensityFloorTest {

    private static final long JOB_ID = 78L;
    private static final long SPACE_ID = 9L;
    private static final long PROJECT_ID = 3L;
    private static final String KEY_FLOOR = "ai.mock.recall-density-floor-per-ktoken";
    private static final String WARN_MARKER = "Mock 起草密度下限闸";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final MockDraftArtifactGateway artifactGateway =
            mock(MockDraftArtifactGateway.class);
    private final MockDraftGenServiceImpl service = new MockDraftGenServiceImpl(
            coreGateway, llmChain, llmRouter, artifactGateway, new ObjectMapper());

    private ListAppender<ILoggingEvent> appender;

    private static final String VALID_LLM_JSON = """
            {"items":[{"name":"下单成功桩","method":"POST","path":"/api/orders",
            "pathMatchType":"exact","responseJson":"{\\"status\\":200}"}]}""";

    private static final String UNIT_RULE = "订单服务返回 200 且耗时低于 200 毫秒。";

    @BeforeEach
    void attachLogCapture() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(MockDraftGenServiceImpl.class))
                .addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(MockDraftGenServiceImpl.class))
                .detachAppender(appender);
    }

    private static MockAiJobInfoRemote job(String prompt) {
        String inputJson;
        try {
            inputJson = new ObjectMapper().writeValueAsString(
                    Map.of("prompt", prompt, "jobType", "nl-stub"));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return new MockAiJobInfoRemote(JOB_ID, SPACE_ID, "nl-stub", "queued", inputJson,
                "{}", null, null);
    }

    private void stubRun(String prompt) {
        when(coreGateway.getMockJob(JOB_ID)).thenReturn(job(prompt));
        when(llmRouter.route(PROJECT_ID)).thenReturn(new LlmEndpointConfig("openai",
                "routed-model", "dummy-key", "http://localhost:4000/v1",
                LlmEndpointConfig.SOURCE_CONFIG));
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
        when(artifactGateway.uploadDraftBundle(eq(JOB_ID), anyString(), any(byte[].class)))
                .thenReturn("3/mock/202610/abcd1234-draft.json");
    }

    private boolean warnSeen() {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage()
                .startsWith(WARN_MARKER));
    }

    /** 4788 source chars -> 3591 estimated tokens -> floor 20.3 demands 73
     *  rules; the single drafted item must trip the WARN face. */
    @Test
    void missingKeyRow_fallsBackToFactoryFloor_underFloorItemsWarnsNumbers() {
        stubRun(UNIT_RULE.repeat(168));

        service.generate(new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "nl-stub",
                11L, "pub-token-1"));

        assertTrue(warnSeen(), "a shortfall must name the floor verdict on WARN");
        String line = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(m -> m.startsWith(WARN_MARKER)).findFirst().orElse("");
        assertTrue(line.contains("实收=1") && line.contains("条数下限="),
                "the WARN line carries the actual/expected numbers: " + line);
    }

    @Test
    void explicitZeroKey_checkOff_staysSilent() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("0");
        stubRun(UNIT_RULE.repeat(168));

        service.generate(new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "nl-stub",
                11L, "pub-token-1"));

        assertFalse(warnSeen(), "an explicit 0 keeps the floor fully out");
    }

    @Test
    void tinySource_floorAlreadyMet_staysSilent() {
        stubRun("为订单服务生成下单成功桩");

        service.generate(new MockGenMessage(JOB_ID, SPACE_ID, PROJECT_ID, "nl-stub",
                11L, "pub-token-1"));

        assertFalse(warnSeen(), "a met floor must stay silent");
    }
}
