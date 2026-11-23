package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.component.DraftSeqAllocator;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * One-key undo behind the dialog : pressing undo
 * must NOT move any business row — it files a fresh CASE_ROLLBACK draft
 * card aimed at the version that was live before the last accepted write,
 * so the human confirmation door stays the only thing that writes. The card
 * is cut against the session's most recent applied case write (create or
 * rollback alike) and refuses — inserting nothing — when there is nothing
 * to undo, when the applied row carries no version anchor, when the case
 * lives outside the session's project, or when the case already sits at the
 * pre-accept version.
 * <p>Mocks only isolate the ledger infrastructure (L2 posture); the landing
 * itself rides {@code CaseRollbackDraftStrategy}, covered by its own tests.
 */
class UndoCaseDraftServiceImplTest {

    private NamedParameterJdbcTemplate named;
    private ChatSessionService sessionService;
    private CaseService caseService;
    private DraftSeqAllocator draftSeqAllocator;
    private UndoCaseDraftServiceImpl service;

    @BeforeEach
    void setUp() {
        named = mock(NamedParameterJdbcTemplate.class);
        sessionService = mock(ChatSessionService.class);
        caseService = mock(CaseService.class);
        draftSeqAllocator = mock(DraftSeqAllocator.class);
        service = new UndoCaseDraftServiceImpl(named, sessionService, caseService,
                draftSeqAllocator, new ObjectMapper());
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
    }

    @Test
    void undoFilesARollbackCardOntoThePreAcceptVersionAndWritesNothingElse() {
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, 3));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 4));
        when(draftSeqAllocator.nextSeq(42L)).thenReturn(7);
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(900L);

        long cardId = service.undoLastAcceptedWrite(7L);

        assertEquals(900L, cardId, "the new pending card's ledger id is handed back");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        MapSqlParameterSource bound = (MapSqlParameterSource) params.getValue();
        String insert = sql.getValue();
        assertTrue(insert.contains("message_id"),
                "the card's ledger insert carries the presentation anchor column: " + insert);
        assertEquals("CASE_ROLLBACK", (String) bound.getValue("objectType"),
                "undo reuses the existing rollback kind — no new object type");
        assertEquals(42L, bound.getValue("targetId"));
        assertEquals(4, bound.getValue("baseVersion"),
                "the card anchor is the version live at card time (optimistic lock)");
        assertEquals(1, bound.getValue("status"),
                "the card starts pending: confirmation stays the only write door");
        assertEquals(100L, bound.getValue("projectId"));
        String payload = (String) bound.getValue("payloadJson");
        assertTrue(payload.contains("\"targetVersion\":3"),
                "undo target = the version before the accepted write: " + payload);
        assertTrue(payload.contains("\"caseId\":42"), payload);
    }

    @Test
    void appliedRollbackIsUndoneExactlyLikeAnAppliedWrite() {
        // A second undo presses against the undo itself: same shape, no
        // once-only special case, the shared chain still owns no-op refusals.
        whenLatestApplied(appliedRow("CASE_ROLLBACK", 42L, 5));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 6));
        when(draftSeqAllocator.nextSeq(42L)).thenReturn(8);
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(901L);

        long cardId = service.undoLastAcceptedWrite(7L);

        assertEquals(901L, cardId);
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(anyString(), params.capture(), eq(Long.class));
        MapSqlParameterSource bound = (MapSqlParameterSource) params.getValue();
        assertEquals(6, bound.getValue("baseVersion"),
                "anchor = the live version when the card was cut");
        assertTrue(((String) bound.getValue("payloadJson")).contains("\"targetVersion\":5"),
                "undo of an undo returns to the version the rollback came from");
    }

    @Test
    void nothingAcceptedYetRefusesAndFilesNoCard() {
        whenLatestApplied();

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.undoLastAcceptedWrite(7L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, error.getCode().intValue());
        assertNoLedgerInsert();
    }

    @Test
    void appliedCreateWithoutVersionAnchorRefusesAndFilesNoCard() {
        // A create proposal has no pre-accept version to return to — undo
        // must refuse loudly instead of guessing one (deleting the case
        // would be an unreviewed write).
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, null));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.undoLastAcceptedWrite(7L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, error.getCode().intValue());
        assertNoLedgerInsert();
    }

    @Test
    void appliedRowWithoutTargetRefusesAndFilesNoCard() {
        Map<String, Object> row = appliedRow("CASE_WRITE", 42L, 3);
        row.put("target_id", null);
        whenLatestApplied(row);

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.undoLastAcceptedWrite(7L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, error.getCode().intValue());
        assertNoLedgerInsert();
    }

    @Test
    void caseOutsideTheSessionProjectIsRefusedBeforeAnyCard() {
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, 3));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 200L, 4));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.undoLastAcceptedWrite(7L));
        assertEquals(BizErrorCode.FORBIDDEN, error.getCode().intValue());
        assertNoLedgerInsert();
    }

    @Test
    void caseAlreadyAtThePreAcceptVersionRefusesAsNoOp() {
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, 3));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 3));

        ServiceException error = assertThrows(ServiceException.class,
                () -> service.undoLastAcceptedWrite(7L));
        assertEquals(BizErrorCode.VALIDATION_ERROR, error.getCode().intValue());
        assertTrue(error.getMessage().contains("版本"),
                "the refusal names the version situation for the human");
        assertNoLedgerInsert();
    }

    @Test
    void undoAnchorsTheCardOnTheNewestSealedAssistantMessage() {
        // segment 3d: an invisible card can never be confirmed, so the insert
        // must bind the presentation anchor resolved from the message table.
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, 3));
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of(808L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 4));
        when(draftSeqAllocator.nextSeq(42L)).thenReturn(9);
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(902L);

        service.undoLastAcceptedWrite(7L);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        MapSqlParameterSource bound = (MapSqlParameterSource) params.getValue();
        assertEquals(808L, bound.getValue("messageId"),
                "the undo card joins the newest sealed assistant row of the session");
        assertTrue(sql.getValue().contains(":messageId"),
                "the ledger insert binds the anchor instead of a hard NULL: " + sql.getValue());
    }

    @Test
    void undoFilesTheCardUnanchoredWhenNothingIsSealedYet() {
        // Honest absence: no sealed assistant row -> the card still files
        // (the applied write exists) but binds no anchor; nothing is faked.
        whenLatestApplied(appliedRow("CASE_WRITE", 42L, 3));
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(List.of());
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 4));
        when(draftSeqAllocator.nextSeq(42L)).thenReturn(10);
        when(named.queryForObject(anyString(), any(MapSqlParameterSource.class), eq(Long.class)))
                .thenReturn(903L);

        service.undoLastAcceptedWrite(7L);

        ArgumentCaptor<SqlParameterSource> params =
                ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(named).queryForObject(anyString(), params.capture(), eq(Long.class));
        assertNull(((MapSqlParameterSource) params.getValue()).getValue("messageId"),
                "no sealed assistant message means no invented anchor");
    }

    // ---- fixtures ----------------------------------------------------------

    private void whenLatestApplied(Map<String, Object>... rows) {
        when(named.queryForList(anyString(), any(MapSqlParameterSource.class)))
                .thenReturn(List.of(rows));
    }

    private void assertNoLedgerInsert() {
        verify(named, never()).queryForObject(anyString(),
                any(MapSqlParameterSource.class), eq(Long.class));
    }

    private static Map<String, Object> appliedRow(String objectType, Long targetId,
                                                  Integer baseVersion) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 511L);
        row.put("object_type", objectType);
        row.put("target_id", targetId);
        row.put("base_version", baseVersion);
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
