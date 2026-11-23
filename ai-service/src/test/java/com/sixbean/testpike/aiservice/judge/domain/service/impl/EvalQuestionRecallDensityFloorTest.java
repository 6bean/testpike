package com.sixbean.testpike.aiservice.judge.domain.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenRequest;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EF batch piece 2: the judge question-gen door consumes the
 * single-source RecallDensityGate verdict at both close-out faces (the
 * synchronous preview tail and the background job tail share one helper
 * call), naming the numbers on one WARN line. Per-domain hot key
 * ai.judge.recall-density-floor-per-ktoken, factory 19.0 — the recalibrated
 * low value of the two measured passes (V503) so a healthy run never trips.
 * <p>L2 posture: infrastructure mocks only, same face as
 * EvalQuestionGenGroupedIntakeTest.
 */
class EvalQuestionRecallDensityFloorTest {

    private static final long DOC_ID = 94L;
    private static final long PROJECT_ID = 34L;
    private static final String KEY_FLOOR = "ai.judge.recall-density-floor-per-ktoken";
    private static final String WARN_MARKER = "造题密度下限闸";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final EvalQuestionGenServiceImpl service = new EvalQuestionGenServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore, null);

    private ListAppender<ILoggingEvent> appender;

    private static final String VALID_LLM_JSON = """
            {"items":[{"question":"订单创建接口用什么方法？","answer":"POST /api/v1/orders",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    private static final String UNIT_FACT = "验收标准：创建订单接口 POST /api/v1/orders 返回 201。";

    @BeforeEach
    void attachLogCapture() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(EvalQuestionGenServiceImpl.class))
                .addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(EvalQuestionGenServiceImpl.class))
                .detachAppender(appender);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private void stubRun(String content) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(
                Map.of("seq", 1, "chapter", "验收标准", "content", content)));
        when(coreGateway.getSysConfig("judge.qa-gen.group-overlap-ratio")).thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
    }

    private boolean warnSeen() {
        return appender.list.stream().anyMatch(e -> e.getFormattedMessage()
                .startsWith(WARN_MARKER));
    }

    /** 4830 dense chars -> ~5152 planner tokens -> factory floor 19.0 demands
     *  98 questions; the single returned pair must trip the WARN face. */
    @Test
    void missingKeyRow_fallsBackToFactoryFloor_underFloorItemsWarnsNumbers() {
        stubRun(UNIT_FACT.repeat(125));

        service.generate(new QaGenRequest(PROJECT_ID, DOC_ID, null, 17L));

        assertTrue(warnSeen(), "a shortfall must name the floor verdict on WARN");
    }

    @Test
    void explicitZeroKey_checkOff_staysSilent() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("0");
        stubRun(UNIT_FACT.repeat(125));

        service.generate(new QaGenRequest(PROJECT_ID, DOC_ID, null, 17L));

        assertFalse(warnSeen(), "an explicit 0 keeps the floor fully out");
    }

    /** Estimator-independent silence face: a low explicit floor (5/k-token
     *  still needs ~200 tokens to demand a second item) keeps this short
     *  source under any tokenizer family. */
    @Test
    void tinySource_floorAlreadyMet_staysSilent() {
        when(coreGateway.getSysConfig(KEY_FLOOR)).thenReturn("5");
        stubRun(UNIT_FACT);

        service.generate(new QaGenRequest(PROJECT_ID, DOC_ID, null, 17L));

        assertFalse(warnSeen(), "a met floor must stay silent");
    }
}
