package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 * Flaky-isolation draft landing channel (AO batch , S2 closed
 * loop step 2-3: judgement proposes, the human door decides).
 * <p>The strategy must land through the SAME {@code CaseService.setFlaky}
 * chain the workbench one-click isolation uses (red line: no parallel write
 * path, audit + idempotent reason refresh come free), and it must refuse —
 * writing nothing — when the card points at a foreign-project case or at a
 * stale version anchor.
 */
class FlakyIsolationDraftFlowTest {

    private CaseService caseService;
    private ChatSessionService sessionService;
    private FlakyIsolationDraftStrategy strategy;

    @BeforeEach
    void setUp() {
        caseService = mock(CaseService.class);
        sessionService = mock(ChatSessionService.class);
        strategy = new FlakyIsolationDraftStrategy(caseService, sessionService);
    }

    @Test
    void registersUnderTheFlakyIsolationKind() {
        assertEquals("FLAKY_ISOLATION", strategy.objectType());
        Optional<DraftObjectType> resolved = DraftObjectType.resolveOrDefault("FLAKY_ISOLATION");
        assertEquals(Optional.of(DraftObjectType.FLAKY_ISOLATION), resolved);
    }

    @Test
    void confirmedCardIsolatesTheCaseThroughTheSharedSetFlakyChain() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3, 0));
        CaseInfo landed = caseInfo(42L, 100L, 4, 1);
        when(caseService.setFlaky(9L, 42L, true, "近 5 次执行失败翻转 3 次，疑似环境时序抖动"))
                .thenReturn(landed);

        Integer applied = strategy.land(draftRow(42L, 3,
                "{\"reason\":\"近 5 次执行失败翻转 3 次，疑似环境时序抖动\"}"), 9L);

        assertEquals(4, applied, "applied version = the case version the shared chain produced");
        verify(caseService).setFlaky(eq(9L), eq(42L), eq(true),
                eq("近 5 次执行失败翻转 3 次，疑似环境时序抖动"));
    }

    @Test
    void blankReasonFallsBackToTheSharedChainDefault() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3, 0));
        when(caseService.setFlaky(anyLong(), anyLong(), anyBoolean(), any()))
                .thenReturn(caseInfo(42L, 100L, 4, 1));

        strategy.land(draftRow(42L, 3, "{\"reason\":\"\"}"), 9L);

        ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);
        verify(caseService).setFlaky(eq(9L), eq(42L), eq(true), reason.capture());
        // null/blank reason is not rejected here — setFlaky owns the default
        // wording, so the strategy must pass the raw value through untouched.
        assertEquals("", reason.getValue());
    }

    @Test
    void foreignProjectCaseIsRefusedAndNothingIsIsolated() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 200L, 3, 0));

        assertThrows(ServiceException.class, () -> strategy.land(
                draftRow(42L, 3, "{\"reason\":\"r\"}"), 9L));
        verify(caseService, never()).setFlaky(anyLong(), anyLong(), anyBoolean(), anyString());
    }

    @Test
    void staleVersionAnchorIsRefusedWithThePlainWordConflict() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 5, 0));

        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                draftRow(42L, 3, "{\"reason\":\"r\"}"), 9L));
        assertEquals(true, error.getMessage().contains("版本"),
                "conflict wording must name the version drift for the human");
        verify(caseService, never()).setFlaky(anyLong(), anyLong(), anyBoolean(), anyString());
    }

    @Test
    void cardWithoutATargetCaseCannotLand() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        Map<String, Object> row = draftRow(42L, 3, "{\"reason\":\"r\"}");
        row.put("target_id", null);

        assertThrows(ServiceException.class, () -> strategy.land(row, 9L));
        verify(caseService, never()).setFlaky(anyLong(), anyLong(), anyBoolean(), anyString());
    }

    // ---- fixtures ----------------------------------------------------------

    private static Map<String, Object> draftRow(long caseId, int baseVersion, String payload) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 501L);
        row.put("session_id", 7L);
        row.put("object_type", "FLAKY_ISOLATION");
        row.put("target_id", caseId);
        row.put("base_version", baseVersion);
        row.put("payload_json", payload);
        return row;
    }

    private static ChatSessionInfo session(long id, long projectId) {
        return mock(ChatSessionInfo.class, inv -> {
            if ("projectId".equals(inv.getMethod().getName())) {
                return projectId;
            }
            return null;
        });
    }

    private static CaseInfo caseInfo(long id, long projectId, int version, int flaky) {
        return mock(CaseInfo.class, inv -> switch (inv.getMethod().getName()) {
            case "id" -> id;
            case "projectId" -> projectId;
            case "version" -> version;
            case "flaky" -> flaky;
            default -> null;
        });
    }
}
