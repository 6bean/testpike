package com.sixbean.testpike.core.analysis.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.analysis.api.dto.AssertionDensityView;
import com.sixbean.testpike.core.analysis.api.dto.CoverageSummaryView;
import com.sixbean.testpike.core.analysis.api.dto.AnalysisRunRequest;
import com.sixbean.testpike.core.analysis.domain.component.AnalysisFunctionDispatcher;
import com.sixbean.testpike.core.analysis.domain.service.SufficiencyAnalysisService;
import com.sixbean.testpike.core.tool.domain.component.ToolAccessGate;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Internal door + function dispatch of the read-only analysis channel
 * : the dialog analysis tool posts a function name plus
 * optional filters to this door and the server computes the numbers on the
 * project segment it carries.
 * <p>Asserted faces: ① the transport identity rule is fail-closed — a missing
 * or non-positive {@code X-Operator-Id} is refused before the visibility check
 * and before any read runs (same posture as the aggregate-query door);
 * ② the project-slice re-check happens inside the dispatcher, keyed on that
 * identity, and its refusal propagates untouched; ③ exactly the registry's
 * read-only analysis family is callable, and the callable set is proven equal
 * to {@code GenFunction.isAnalysis} so the door can never drift from the
 * single-source registry; ④ a function outside that family (a fabrication
 * function, a name that does not exist, or the pre-rename snake_case spelling)
 * is refused by name with the callable set named back; ⑤ a filter the named
 * function does not declare is refused instead of being silently dropped —
 * dropping it would print project-wide numbers under a narrowed question;
 * ⑥ absent filters mean "whole project", never an error.
 * <p>The read service and the gate are mocked here because both already have
 * their own real-DB spectra (SufficiencyAnalysisServiceTest proves the numbers);
 * this class pins the transport contract and the dispatch judgement only.
 */
class AnalysisDialogDoorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final SufficiencyAnalysisService readService = mock(SufficiencyAnalysisService.class);
    private final ToolAccessGate accessGate = mock(ToolAccessGate.class);
    private final AnalysisFunctionDispatcher dispatcher =
            new AnalysisFunctionDispatcher(readService,
            mock(com.sixbean.testpike.core.plans.domain.component.RehearsalDraftComposer.class),
            mock(com.sixbean.testpike.core.plans.domain.component.RehearsalRiskReportComposer.class),
            accessGate);
    private final AnalysisInternalController controller =
            new AnalysisInternalController(dispatcher);

    private static CoverageSummaryView coverageView(long projectId, String chapter, String endType) {
        return new CoverageSummaryView(projectId, chapter, endType, 40, 31,
                List.of("REQ-12"), false, 0, false, -1, null);
    }

    private static AssertionDensityView densityView(long projectId, String endType) {
        return new AssertionDensityView(projectId, endType, 25, 180, 66, 0.37, 3, List.of());
    }

    private static AnalysisRunRequest request(String function, String argsJson) throws Exception {
        return new AnalysisRunRequest(function,
                argsJson == null ? null : MAPPER.readTree(argsJson));
    }

    // ---------- ① fail-closed transport identity ----------

    @Test
    void missingOperatorHeaderIsRefusedBeforeAnyRead() throws Exception {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, null, request("coverageSummary", null)));

        assertTrue(refused.getMessage().contains("操作人"), refused.getMessage());
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
        verify(accessGate, never()).requireProjectVisible(any(), any());
    }

    @Test
    void zeroOperatorHeaderIsRefusedToo() throws Exception {
        assertThrows(ServiceException.class,
                () -> controller.run(3L, 0L, request("coverageSummary", null)));
        verify(accessGate, never()).requireProjectVisible(any(), any());
    }

    // ---------- ② named function on the project segment ----------

    @Test
    void coverageSummaryRunsOnTheSliceAfterTheVisibilityRecheck() throws Exception {
        when(readService.coverageSummary(3L, "登录", "web")).thenReturn(coverageView(3L, "登录", "web"));

        var got = controller.run(3L, 7L,
                request("coverageSummary", "{\"chapter\":\"登录\",\"endType\":\"web\"}"));

        verify(accessGate).requireProjectVisible(eq(3L), eq(7L));
        CoverageSummaryView view = (CoverageSummaryView) got.getData();
        assertEquals(40, view.total());
        assertEquals(31, view.covered());
        assertEquals("登录", view.chapter());
        verify(readService).coverageSummary(eq(3L), eq("登录"), eq("web"));
    }

    @Test
    void assertionDensityRunsTheOtherFunctionWithNoArgsMeaningWholeProject() throws Exception {
        when(readService.assertionDensity(eq(3L), isNull())).thenReturn(densityView(3L, null));

        var got = controller.run(3L, 7L, request("assertionDensity", null));

        assertEquals(180, ((AssertionDensityView) got.getData()).totalSteps());
        verify(readService).assertionDensity(eq(3L), isNull());
    }

    @Test
    void visibilityRefusalPropagatesBeforeAnyRead() throws Exception {
        doThrow(new ServiceException("无权访问该项目的工具装配", 40301))
                .when(accessGate).requireProjectVisible(3L, 7L);

        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("coverageSummary", null)));

        assertTrue(refused.getMessage().contains("无权"), refused.getMessage());
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    // ---------- ③ callable set equals the registry analysis family ----------

    @Test
    void callableSetEqualsTheRegistryAnalysisFamilyExactly() {
        Set<String> registry = Arrays.stream(GenFunction.values())
                .filter(GenFunction::isAnalysis)
                .map(GenFunction::functionName)
                .collect(Collectors.toSet());

        assertEquals(registry, dispatcher.callableFunctions(),
                "the door must offer exactly what the single-source registry marks read-only analysis");
        assertEquals(Set.of("coverageSummary", "assertionDensity", "rehearsalDraft",
                        "rehearsalRiskReport"), registry,
                "registry literal names are the door contract — no second spelling");
    }

    // ---------- ④ out-of-family functions are refused by name ----------

    @Test
    void fabricationFunctionIsRefusedAndToldWhereItRuns() throws Exception {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("md5", "{\"text\":\"a\"}")));

        assertTrue(refused.getMessage().contains("md5"), refused.getMessage());
        assertTrue(refused.getMessage().contains("coverageSummary"), refused.getMessage());
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    @Test
    void unknownFunctionNameIsRefusedIncludingTheOldSnakeSpelling() throws Exception {
        for (String smuggled : List.of("coverage_summary", "assertion_density", "doesNotExist")) {
            ServiceException refused = assertThrows(ServiceException.class,
                    () -> controller.run(3L, 7L, request(smuggled, null)), smuggled);
            assertTrue(refused.getMessage().contains(smuggled), refused.getMessage());
        }
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    // ---------- ⑤ undeclared filter is refused, never silently dropped ----------

    @Test
    void filterTheFunctionDoesNotDeclareIsRefusedByName() throws Exception {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("assertionDensity", "{\"chapter\":\"登录\"}")));

        assertTrue(refused.getMessage().contains("chapter"), refused.getMessage());
        assertTrue(refused.getMessage().contains("assertionDensity"), refused.getMessage());
        verify(readService, never()).assertionDensity(anyLong(), any());
    }

    @Test
    void nonObjectArgsAreRefused() throws Exception {
        assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("coverageSummary", "[\"登录\"]")));
        assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("coverageSummary", "\"登录\"")));
        verify(readService, never()).coverageSummary(anyLong(), any(), any());
    }

    @Test
    void blankFunctionNameIsRefusedWithTheCallableSetNamed() throws Exception {
        ServiceException refused = assertThrows(ServiceException.class,
                () -> controller.run(3L, 7L, request("  ", null)));
        assertTrue(refused.getMessage().contains("assertionDensity"), refused.getMessage());
    }
}
