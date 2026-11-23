package com.sixbean.testpike.aiservice.gen.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stage-2 mechanical gap-fill wiring : the deterministic
 * assembly lands in the format-residual stop-loss BEFORE the first budgeted
 * re-issue round, its notes ride the shared process ledger, and a door outage
 * keeps the ORIGINAL draft + verdict instead of masquerading as "project has
 * no database" (the four ledger branches mirror the filing's acceptance list;
 * the filler's own rule surface stays in ScriptMechanicalGapFillerTest).
 */
class AiGenServiceImplStage2MechanicalGapFillTest {

    private static final long TASK_ID = 690001L;
    private static final long PROJECT_ID = 201052L;

    /** Contract-breaking non-UI steps of the live signature family: sql
     *  without conn + console.assert with a spurious locator. */
    private static final String GAP_DRAFT = """
            {"version":"v2","caseId":"TC-690","env":{"ENV":"SIT"},
             "setup":[],
             "steps":[
               {"action":"sql.assert","conn":"","sql":"SELECT 1","expect":[]},
               {"action":"console.assert","level":"error","target":{"css":"#x","chain":["css"]}}]}""";

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);
    private final AiGenServiceImpl service = GenWiringHarness.assembleWithLedger(
            coreGateway, mock(LlmFallbackChain.class), mock(ProjectLlmRouter.class), store);

    @Test
    void uniqueCandidate_fillsConnAndNotesLocatorRemoval() {
        when(coreGateway.listEnabledConnectionNames(PROJECT_ID, "SIT"))
                .thenReturn(List.of("sit_db"));

        String filled = service.applyMechanicalGapFill(TASK_ID, PROJECT_ID, GAP_DRAFT, "[g1]");

        assertTrue(filled.contains("\"conn\":\"sit_db\""), filled);
        assertFalse(filled.contains("\"target\""), filled);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store).record(anyString(), anyString(), anyString(), eq("step"),
                summary.capture(), any());
        assertTrue(summary.getValue().contains("console.assert"));
        assertTrue(summary.getValue().startsWith("[g1]"));
    }

    @Test
    void ambiguousCandidates_neverGuesses_listsCandidatesInLedger() {
        when(coreGateway.listEnabledConnectionNames(PROJECT_ID, "SIT"))
                .thenReturn(List.of("sit_db", "sit_rw"));

        String filled = service.applyMechanicalGapFill(TASK_ID, PROJECT_ID, GAP_DRAFT, "");

        assertFalse(filled.contains("\"conn\":\"sit_db\""), filled);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).record(anyString(), anyString(),
                anyString(), eq("step"), summary.capture(), any());
        assertTrue(summary.getAllValues().stream()
                .anyMatch(s -> s.contains("候选：sit_db、sit_rw") || s.contains("sit_db、sit_rw")),
                String.valueOf(summary.getAllValues()));
    }

    @Test
    void noConnection_dropsSqlStepWithDegradationNote() {
        when(coreGateway.listEnabledConnectionNames(PROJECT_ID, "SIT"))
                .thenReturn(List.of());

        String filled = service.applyMechanicalGapFill(TASK_ID, PROJECT_ID, GAP_DRAFT, "");

        assertFalse(filled.contains("sql.assert"), filled);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store, org.mockito.Mockito.atLeastOnce()).record(anyString(), anyString(),
                anyString(), eq("step"), summary.capture(), any());
        assertTrue(summary.getAllValues().stream()
                .anyMatch(s -> s.contains("数据库连接")), String.valueOf(summary.getAllValues()));
    }

    @Test
    void doorOutage_keepsOriginalDraftAndVerdict() {
        when(coreGateway.listEnabledConnectionNames(PROJECT_ID, "SIT"))
                .thenThrow(new ServiceException("core 不可达", 50003));

        assertEquals(GAP_DRAFT, service.applyMechanicalGapFill(TASK_ID, PROJECT_ID, GAP_DRAFT, ""));
        verify(store, never()).record(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
    }

    @Test
    void unparsableDraft_passesThroughForValidatorVerdict() {
        String broken = "not a json at all";
        assertEquals(broken, service.applyMechanicalGapFill(TASK_ID, PROJECT_ID, broken, ""));
        verify(coreGateway, never()).listEnabledConnectionNames(eq(PROJECT_ID), anyString());
    }
}
