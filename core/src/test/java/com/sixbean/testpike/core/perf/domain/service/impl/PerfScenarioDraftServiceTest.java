package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioDraftRequest;
import com.sixbean.testpike.core.perf.domain.component.PerfAiSwitchGate;
import com.sixbean.testpike.core.perf.domain.component.PerfStepActionGate;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfDraftClient;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;

/**
 * Core orchestration leg for AI load-test scenario drafting (B-11 :
 * the switch gate must refuse before any model hop is spent, and the reply's
 * steps must be re-validated through the single step gate owned by core —
 * violating steps are trimmed here (never a second spec elsewhere) so the
 * wizard only ever prefills steps that the scenario save gate will accept.
 * Trimming is honest: the dropped steps and their plain-word reasons travel
 * back with the draft so the UI can name what was discarded.
 */
class PerfScenarioDraftServiceTest {

    /** ConfigService stub backed by an in-memory row map (PerfAiSwitchGateTest precedent). */
    private static ConfigService mapConfig(Map<String, String> rows) {
        ConfigService configService = Mockito.mock(ConfigService.class);
        Mockito.when(configService.getValue(Mockito.anyString(), Mockito.any()))
                .thenAnswer(invocation -> {
                    String key = invocation.getArgument(0);
                    String fallback = invocation.getArgument(1);
                    return rows.getOrDefault(key, fallback);
                });
        return configService;
    }

    private static PerfScenarioDraftRequest request() {
        return new PerfScenarioDraftRequest(11L, "对下单接口施压 100 RPS 持续 5 分钟");
    }

    private static Map<String, Object> legalStep() {
        return new HashMap<>(Map.of(
                "action", "http.request",
                "method", "GET",
                "url", "{{ENV:BASE}}/orders"));
    }

    private static PerfScenarioDraftServiceImpl service(Map<String, String> configRows,
                                                        AiServicePerfDraftClient client) {
        return new PerfScenarioDraftServiceImpl(
                new PerfAiSwitchGate(mapConfig(configRows)),
                client,
                new PerfStepActionGate(),
                new ObjectMapper());
    }

    @Test
    @SuppressWarnings("unchecked")
    void closedSwitchRefusesBeforeAnyModelHop() {
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        PerfScenarioDraftServiceImpl svc = service(new HashMap<>(), client);

        ServiceException e = assertThrows(ServiceException.class, () -> svc.draft(request()));
        assertEquals(BizErrorCode.FORBIDDEN, e.getCode());
        assertTrue(e.getMessage().contains("ai.perf.scenario-draft"),
                "refusal must name the exact key an operator flips: " + e.getMessage());
        Mockito.verifyNoInteractions(client);
    }

    @Test
    @SuppressWarnings("unchecked")
    void legalStepsSurviveAndViolatingOnesAreTrimmed() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        // one legal http.request step + one browser action + one blank-url step
        Mockito.when(client.draft(any())).thenReturn(new HashMap<>(Map.of("steps", List.of(
                legalStep(),
                new HashMap<>(Map.of("action", "browser.click", "url", "https://x/")),
                new HashMap<>(Map.of("action", "http.request", "method", "GET", "url", " "))))));

        Map<String, Object> draft = service(rows, client).draft(request());

        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(1, steps.size());
        assertEquals("http.request", steps.get(0).get("action"));
        assertEquals(2, draft.get("droppedCount"));
        List<String> reasons = (List<String>) draft.get("droppedReasons");
        assertEquals(2, reasons.size());
        // reasons keep the step gate's own wording (single message corpus, no second copy)
        assertTrue(reasons.get(0).contains("http.request") || reasons.get(0).contains("url"),
                "dropped reasons must carry the gate wording: " + reasons);
    }

    @Test
    @SuppressWarnings("unchecked")
    void draftStepHeadersMaskAuthShapedValuesBeforeTheWizardSeesThem() {
        // U-10: a model reply may carry an Authorization header (echoed from the
        // user's material or invented). The draft read-out prefill must never
        // show a raw secret under an auth-shaped name — star-value it through
        // the shared name spectrum, keep ordinary headers intact for debugging.
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        Map<String, Object> step = new HashMap<>(Map.of(
                "action", "http.request", "method", "GET", "url", "{{ENV:BASE}}/orders",
                "headers", new HashMap<>(Map.of(
                        "Authorization", "Bearer sk-live-abc",
                        "X-Trace", "keep-me"))));
        Mockito.when(client.draft(any())).thenReturn(new HashMap<>(Map.of("steps", List.of(step))));

        Map<String, Object> draft = service(rows, client).draft(request());
        List<Map<String, Object>> steps = (List<Map<String, Object>>) draft.get("steps");
        assertEquals(1, steps.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> headers = (Map<String, Object>) steps.get(0).get("headers");
        assertEquals("***", headers.get("Authorization"));
        assertEquals("keep-me", headers.get("X-Trace"));
    }

    @Test
    void aFullyTrimmedDraftStaysHonestWithEmptySteps() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        Mockito.when(client.draft(any())).thenReturn(new HashMap<>(Map.of("steps", List.of(
                new HashMap<>(Map.of("action", "sql.query"))))));

        Map<String, Object> draft = service(rows, client).draft(request());

        assertEquals(0, ((List<?>) draft.get("steps")).size());
        assertEquals(1, draft.get("droppedCount"));
    }

    @Test
    void blankDraftInputsRefuseBeforeModelHop() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        PerfScenarioDraftServiceImpl svc = service(rows, client);

        ServiceException noProject = assertThrows(ServiceException.class,
                () -> svc.draft(new PerfScenarioDraftRequest(null, "打一下下单")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noProject.getCode());

        ServiceException noIntent = assertThrows(ServiceException.class,
                () -> svc.draft(new PerfScenarioDraftRequest(11L, "  ")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, noIntent.getCode());

        Mockito.verifyNoInteractions(client);
    }

    @Test
    void clientFailureKeepsItsOwnCodeAndMessage() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        Mockito.when(client.draft(any())).thenThrow(
                new ServiceException("模型返回的不是可解析的压测步骤 JSON", BizErrorCode.AI_FORMAT_ERROR));

        ServiceException e = assertThrows(ServiceException.class, () -> service(rows, client).draft(request()));
        assertEquals(BizErrorCode.AI_FORMAT_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("压测步骤 JSON"),
                "ai-service wording must ride through untranslated: " + e.getMessage());
    }

    @Test
    void replyWithoutStepsArrayDegradesToEmptyDraftWithZeroDrops() {
        Map<String, String> rows = new HashMap<>();
        rows.put("ai.perf.scenario-draft", "true");
        AiServicePerfDraftClient client = Mockito.mock(AiServicePerfDraftClient.class);
        // ai-service guarantees a steps array; a shape slip must not fake drops
        Mockito.when(client.draft(any())).thenReturn(new HashMap<>(Map.of()));

        Map<String, Object> draft = service(rows, client).draft(request());

        assertEquals(0, ((List<?>) draft.get("steps")).size());
        assertEquals(0, draft.get("droppedCount"));
    }
}
