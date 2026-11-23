package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Version-history dialog landing (rollback side): the confirmed card must
 * land through the SAME {@code CaseService.rollback} chain the case page
 * uses (append-only history, optimistic lock, change_type=4 audit all come
 * from the shared chain — no parallel rollback path), and it must refuse —
 * writing nothing — when the card points at a foreign-project case, at a
 * stale version anchor without the override choice, or carries no target
 * version.
 */
class CaseRollbackDraftStrategyTest {

    private CaseService caseService;
    private ChatSessionService sessionService;
    private CaseRollbackDraftStrategy strategy;

    @BeforeEach
    void setUp() {
        caseService = mock(CaseService.class);
        sessionService = mock(ChatSessionService.class);
        strategy = new CaseRollbackDraftStrategy(caseService, sessionService);
    }

    @Test
    void registersUnderTheCaseRollbackKind() {
        assertEquals("CASE_ROLLBACK", strategy.objectType());
        Optional<DraftObjectType> resolved = DraftObjectType.resolveOrDefault("CASE_ROLLBACK");
        assertEquals(Optional.of(DraftObjectType.CASE_ROLLBACK), resolved);
    }

    @Test
    void confirmedCardRollsBackThroughTheSharedRollbackChain() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3));
        when(caseService.rollback(9L, 42L, 1, 3)).thenReturn(new CaseRollbackResult(42L, 1, 4, 4));

        Integer applied = strategy.land(draftRow(42L, 3, "{\"targetVersion\":1}"), 9L);

        assertEquals(4, applied,
                "applied version = the new version the shared rollback chain produced");
        verify(caseService).rollback(9L, 42L, 1, 3);
    }

    @Test
    void foreignProjectCaseIsRefusedAndNothingRollsBack() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 200L, 3));

        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                draftRow(42L, 3, "{\"targetVersion\":1}"), 9L));
        assertEquals(BizErrorCode.FORBIDDEN, error.getCode().intValue());
        verify(caseService, never()).rollback(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void staleVersionAnchorIsRefusedWithThePlainWordConflict() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 5));

        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                draftRow(42L, 3, "{\"targetVersion\":1}"), 9L));
        assertEquals(BizErrorCode.VERSION_CONFLICT, error.getCode().intValue());
        assertEquals(true, error.getMessage().contains("版本"),
                "conflict wording must name the version drift for the human");
        verify(caseService, never()).rollback(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void forceChoiceReplaysTheRollbackOnTheCurrentVersion() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 5));
        when(caseService.rollback(9L, 42L, 1, 5)).thenReturn(new CaseRollbackResult(42L, 1, 6, 6));

        Integer applied = strategy.land(draftRow(42L, 3, "{\"targetVersion\":1}"), 9L,
                DraftWriteStrategy.ACTION_FORCE);

        assertEquals(6, applied);
        verify(caseService).rollback(9L, 42L, 1, 5);
    }

    @Test
    void cardWithoutATargetCaseCannotLand() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        Map<String, Object> row = draftRow(42L, 3, "{\"targetVersion\":1}");
        row.put("target_id", null);

        assertThrows(ServiceException.class, () -> strategy.land(row, 9L));
        verify(caseService, never()).rollback(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void payloadWithoutATargetVersionIsRefusedBeforeAnythingMoves() {
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3));

        for (String payload : new String[] {"{}", "{\"targetVersion\":0}",
                "{\"targetVersion\":null}", "not-json"}) {
            ServiceException error = assertThrows(ServiceException.class,
                    () -> strategy.land(draftRow(42L, 3, payload), 9L),
                    "payload without a usable target version must refuse: " + payload);
            assertEquals(BizErrorCode.VALIDATION_ERROR, error.getCode().intValue());
        }
        verify(caseService, never()).rollback(anyLong(), anyLong(), anyInt(), anyInt());
    }

    @Test
    void sharedChainRefusalsSurfaceUntouched() {
        // Rollback-to-current and unknown target versions are the chain's
        // own calls to refuse; the strategy must not reword or pre-empt them.
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3));
        when(caseService.rollback(9L, 42L, 3, 3)).thenThrow(
                new ServiceException("目标版本即当前版本，无需回滚", BizErrorCode.VALIDATION_ERROR));

        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                draftRow(42L, 3, "{\"targetVersion\":3}"), 9L));
        assertEquals("目标版本即当前版本，无需回滚", error.getMessage());
    }

    // ---- fixtures ----------------------------------------------------------

    private static Map<String, Object> draftRow(long caseId, int baseVersion, String payload) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 511L);
        row.put("session_id", 7L);
        row.put("object_type", "CASE_ROLLBACK");
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

    private static CaseInfo caseInfo(long id, long projectId, int version) {
        return mock(CaseInfo.class, inv -> switch (inv.getMethod().getName()) {
            case "id" -> id;
            case "projectId" -> projectId;
            case "version" -> version;
            default -> null;
        });
    }
}
