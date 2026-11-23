package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.domain.LlmToolCall;
import com.sixbean.testpike.aiservice.tool.domain.FcPlan;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Routing tests for the read-only ANALYSIS exec kind of {@link ToolDispatcher}.
 * <p>Assertions:
 * 1) kind=5 rows run through the project-sliced core analysis door
 * (runAnalysis with the registry function name + verbatim model filters),
 * never through the generic tool-invoke link;
 * 2) the project segment comes from the server-carried dispatch parameter —
 * the model body never names a project;
 * 3) missing segment / missing function name = fail-closed refusal, zero outbound;
 * 4) door guard refusals (ServiceException) ride back as readable tool
 * messages instead of exceptions (same fail-soft baseline as other kinds);
 * 5) an unknown exec kind still lands on the named default refusal, proving
 * the switch no longer silently swallows new kinds.
 * <p>L2 mocks CoreGateway to isolate the infrastructure HTTP downstream (same
 * approved pattern as ToolDispatcherTest); the live door chain is exercised by
 * the later three-way reconciliation and L3 segment.
 */
class ToolDispatcherAnalysisRoutingTest {

    private static final Long ACTOR_ID = 9L;
    private static final long PROJECT_SLICE = 7L;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ToolDispatcher dispatcher = new ToolDispatcher(coreGateway, MAPPER);

    private static FcPlan planWith(ToolFcAssemblyRemoteItem... items) {
        Map<String, ToolFcAssemblyRemoteItem> index = new LinkedHashMap<>();
        for (ToolFcAssemblyRemoteItem i : items) {
            index.put(i.name(), i);
        }
        return new FcPlan(List.of(), Map.copyOf(index));
    }

    private static ToolFcAssemblyRemoteItem analysisItem(long id, String name, String function) {
        return new ToolFcAssemblyRemoteItem(id, name, 2, 5,
                "{\"type\":\"object\"}", 30000, false, function);
    }

    private static LlmToolCall call(String name, String argsJson) {
        return new LlmToolCall("call-1", name, argsJson);
    }

    @Test
    void analysisRowRoutesThroughServerSlicedDoor() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("coveredRequirements", 12);
        view.put("uncoveredRequirementIds", List.of(31, 52));
        when(coreGateway.runAnalysis(ACTOR_ID, PROJECT_SLICE, "coverageSummary",
                "{\"chapter\":\"登录\"}")).thenReturn(view);

        FcPlan plan = planWith(analysisItem(91, "分析·需求覆盖摘要", "coverageSummary"));
        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                call("分析·需求覆盖摘要", "{\"chapter\":\"登录\"}"),
                ACTOR_ID, null, null, null, PROJECT_SLICE);

        assertTrue(outcome.success(), outcome.content());
        assertTrue(outcome.content().contains("uncoveredRequirementIds"),
                "computed view is serialized verbatim into the tool message: " + outcome.content());
        verify(coreGateway).runAnalysis(ACTOR_ID, PROJECT_SLICE, "coverageSummary",
                "{\"chapter\":\"登录\"}");
    }

    @Test
    void analysisNeverFallsIntoHttpInvokePath() {
        when(coreGateway.runAnalysis(any(), anyLong(), anyString(), any()))
                .thenReturn(Map.of("avgDensity", 1.5));

        FcPlan plan = planWith(analysisItem(92, "分析·断言密度", "assertionDensity"));
        dispatcher.dispatch(plan, call("分析·断言密度", "{}"),
                ACTOR_ID, 42L, null, null, PROJECT_SLICE);

        verify(coreGateway, never()).invokeTool(any(), any(), anyLong(), any(), any(), any());
    }

    @Test
    void analysisWithoutProjectSliceRefusedZeroOutbound() {
        FcPlan plan = planWith(analysisItem(91, "分析·需求覆盖摘要", "coverageSummary"));
        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                call("分析·需求覆盖摘要", "{}"), ACTOR_ID, null, null, null, 0L);

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("项目切片"), outcome.content());
        verify(coreGateway, never()).runAnalysis(any(), anyLong(), anyString(), any());
    }

    @Test
    void analysisRowWithoutFunctionNameRefused() {
        FcPlan plan = planWith(analysisItem(93, "分析·残缺行", null));
        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                call("分析·残缺行", "{}"), ACTOR_ID, null, null, null, PROJECT_SLICE);

        assertFalse(outcome.success());
        verify(coreGateway, never()).runAnalysis(any(), anyLong(), anyString(), any());
    }

    @Test
    void doorGuardRefusalRidesToolMessageNotException() {
        when(coreGateway.runAnalysis(ACTOR_ID, PROJECT_SLICE, "coverageSummary", "{}"))
                .thenThrow(new ServiceException("没有该项目的查看权限（已拒绝执行）",
                        BizErrorCode.FORBIDDEN));

        FcPlan plan = planWith(analysisItem(91, "分析·需求覆盖摘要", "coverageSummary"));
        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                call("分析·需求覆盖摘要", "{}"), ACTOR_ID, null, null, null, PROJECT_SLICE);

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("查看权限"),
                "the door refusal sentence reaches the model for self-correction: "
                        + outcome.content());
    }

    @Test
    void unknownExecKindStillRefusedByNamedDefault() {
        FcPlan plan = planWith(new ToolFcAssemblyRemoteItem(99, "future_tool", 2, 6,
                "{\"type\":\"object\"}", 5000, false));
        ToolCallOutcome outcome = dispatcher.dispatch(plan,
                call("future_tool", "{}"), ACTOR_ID, null, null, null, PROJECT_SLICE);

        assertFalse(outcome.success());
        assertTrue(outcome.content().contains("kind=6"), outcome.content());
    }
}
