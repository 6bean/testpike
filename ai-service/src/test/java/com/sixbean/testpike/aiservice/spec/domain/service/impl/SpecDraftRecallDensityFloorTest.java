package com.sixbean.testpike.aiservice.spec.domain.service.impl;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.doc.domain.component.ExtractFidelityLedgerStore;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackNotice;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.common.mq.AiSpecExtractMessage;
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
 * EF batch piece 2: the spec-extract door consumes the single-source
 * RecallDensityGate verdict at its close-out point and names the numbers on
 * one WARN line (the decision arithmetic itself stays in the gate piece,
 * R40 — this suite only pins the wiring face). Per-domain hot key
 * ai.spec.recall-density-floor-per-ktoken: a missing row falls back to the
 * door's own factory default 11.8 (calibrated low from the measured 19.68
 * items/k-token sample x 0.6 safety factor), an explicit 0 switches the
 * check off, and a met floor must stay silent (no false-positive noise).
 * <p>L2 posture: infrastructure mocks only, same face as
 * SpecDraftExtractGroupedIntakeTest.
 */
class SpecDraftRecallDensityFloorTest {

    private static final long DOC_ID = 93L;
    private static final long PROJECT_ID = 33L;
    private static final String KEY_FLOOR = "ai.spec.recall-density-floor-per-ktoken";
    private static final String WARN_MARKER = "通道 B 密度下限闸";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final LlmFallbackChain llmChain = mock(LlmFallbackChain.class);
    private final ProjectLlmRouter llmRouter = mock(ProjectLlmRouter.class);
    private final ExtractFidelityLedgerStore fidelityStore =
            mock(ExtractFidelityLedgerStore.class);
    private final SpecDraftExtractServiceImpl service = new SpecDraftExtractServiceImpl(
            coreGateway, llmChain, llmRouter, new ObjectMapper(), fidelityStore);

    private ListAppender<ILoggingEvent> appender;

    private static final String VALID_LLM_JSON = """
            {"items":[{"method":"POST","path":"/api/v1/orders","title":"创建订单",
            "sourceAnchor":"创建订单接口 POST /api/v1/orders"}]}""";

    private static final String UNIT_SENTENCE = "创建订单接口 POST /api/v1/orders 校验字段。";

    @BeforeEach
    void attachLogCapture() {
        appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(SpecDraftExtractServiceImpl.class))
                .addAppender(appender);
    }

    @AfterEach
    void detachLogCapture() {
        ((Logger) LoggerFactory.getLogger(SpecDraftExtractServiceImpl.class))
                .detachAppender(appender);
    }

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "routed-model", "dummy-key",
                "http://localhost:4000/v1", LlmEndpointConfig.SOURCE_CONFIG);
    }

    private static Map<String, Object> chunk(String content) {
        return Map.of("seq", 1, "chapter", "接口清单", "content", content);
    }

    private void stubRun(String content) {
        when(llmRouter.route(PROJECT_ID)).thenReturn(endpoint());
        when(coreGateway.getDocumentChunks(DOC_ID)).thenReturn(List.of(chunk(content)));
        when(coreGateway.getSysConfig("apitest.spec-extract.group-overlap-ratio"))
                .thenReturn("0");
        when(llmChain.chat(any(), eq(PROJECT_ID), any(LlmFallbackNotice.class), anyBoolean()))
                .thenReturn(new LlmChatResult(VALID_LLM_JSON, "openai", "routed-model",
                        100, 50, 20L));
    }

    private boolean warnSeen() {
        return appender.list.stream().anyMatch(e -> WARN_MARKER
                .equals(e.getFormattedMessage().substring(0, Math.min(WARN_MARKER.length(),
                        e.getFormattedMessage().length()))));
    }

    /** 4800 dense chars -> ~5120 planner tokens -> default floor 11.8 demands
     *  61 items; the single returned draft must trip the WARN face. */
    @Test
    void missingKeyRow_fallsBackToFactoryFloor_underFloorDraftsWarnsNumbers() {
        stubRun(UNIT_SENTENCE.repeat(160));

        service.extract(new AiSpecExtractMessage(DOC_ID, PROJECT_ID, 15L, null, "pub-1"));

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
        stubRun(UNIT_SENTENCE.repeat(160));

        service.extract(new AiSpecExtractMessage(DOC_ID, PROJECT_ID, 15L, null, "pub-1"));

        assertFalse(warnSeen(), "an explicit 0 keeps the floor fully out");
    }

    /** The known pitfall pinned for this plane: short sources whose own
     *  expected count is already met never fire (no false-positive noise). */
    @Test
    void tinySource_floorAlreadyMet_staysSilent() {
        stubRun(UNIT_SENTENCE);

        service.extract(new AiSpecExtractMessage(DOC_ID, PROJECT_ID, 15L, null, "pub-1"));

        assertFalse(warnSeen(), "a met floor must stay silent");
    }
}
