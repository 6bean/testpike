package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.session.api.dto.CaptureCommittedEntry;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.component.CaptureSessionStore;
import com.sixbean.testpike.core.session.domain.component.LoginStateEntriesComposer;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Screenshot login-state draft landing : a confirmed card must
 * file the checked subset through the SAME {@code LoginStateService.register}
 * ledger chain the CDP wizard uses, reading secret values only from the
 * transient capture slot (they never ride the draft payload). It must refuse —
 * writing nothing — when the slot expired, when it belongs to another chat
 * session, when the check list is empty, or when a checked row is not part of
 * what the human saw (fail-closed whole-card refusal). Success deletes the
 * slot (terminal state, R57) and pins the produced ledger row id onto the card.
 */
class LoginStateDraftStrategyTest {

    private LoginStateService loginStateService;
    private CaptureSessionStore store;
    private ChatSessionService sessionService;
    private JdbcTemplate jdbcTemplate;
    private LoginStateDraftStrategy strategy;

    @BeforeEach
    void setUp() {
        loginStateService = mock(LoginStateService.class);
        store = mock(CaptureSessionStore.class);
        sessionService = mock(ChatSessionService.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        strategy = new LoginStateDraftStrategy(loginStateService, store,
                new LoginStateEntriesComposer(), sessionService, jdbcTemplate);
        when(sessionService.findById(anyLong()))
                .thenReturn(new ChatSessionInfo(5L, 77L, 1L, "t", 0, null, 1, 0L, 0L));
    }

    private Map<String, Object> draftRow(String payloadJson) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 900L);
        row.put("session_id", 5L);
        row.put("object_type", "LOGIN_STATE_WRITE");
        row.put("target_id", 0L);
        row.put("payload_json", payloadJson);
        return row;
    }

    private void stageSlot(long ownerSessionId, List<Map<String, Object>> entries) {
        Map<String, Object> slot = new HashMap<>();
        slot.put("channel", "login-state-screenshot");
        slot.put("sessionId", ownerSessionId);
        slot.put("entries", entries);
        when(store.load("tok-1")).thenReturn(slot);
    }

    private Map<String, Object> stagedCookie(String name, String value) {
        return Map.of("kind", "cookie", "name", name, "origin", ".shop.example",
                "value", value, "expiresAtEpochSecond", 1893456000L);
    }

    @Test
    void landedCardRegistersCheckedCookiesThroughTheLedgerChain() {
        stageSlot(5L, List.of(
                stagedCookie("sid", "s-value"),
                stagedCookie("csrf", "c-value"),
                Map.of("kind", "local_storage", "name", "jwt", "origin", "https://a.example",
                        "value", "j-value", "expiresAtEpochSecond", 1L)));
        String payload = """
                {"stagingToken":"tok-1","stateName":"商城测试账号",
                 "entries":[{"name":"sid","kind":"cookie","selected":true},
                            {"name":"csrf","kind":"cookie","selected":true},
                            {"name":"jwt","kind":"local_storage","selected":false}]}""";

        when(loginStateService.register(eq(77L), any(LoginStateInput.class))).thenReturn(4242L);

        assertNull(strategy.land(draftRow(payload), 1L));

        ArgumentCaptor<LoginStateInput> input = ArgumentCaptor.forClass(LoginStateInput.class);
        verify(loginStateService).register(eq(77L), input.capture());
        LoginStateInput landed = input.getValue();
        assertEquals("商城测试账号", landed.stateName());
        // Only cookies were checked -> cookie_set snapshot, values from the
        // transient slot, the unselected jwt row absent.
        assertEquals(LoginStateEntriesComposer.LEDGER_KIND_COOKIE_SET, landed.kind());
        assertTrue(landed.secret().contains("s-value"));
        assertTrue(landed.secret().contains("c-value"));
        assertTrue(!landed.secret().contains("j-value"));
        assertTrue(landed.credKey().startsWith("capture_"));
        assertTrue(landed.remark().contains("截图登记"));
        // Terminal state: the slot is deleted and the produced row id pinned.
        verify(store).remove("tok-1");
        verify(jdbcTemplate).update(anyString(), eq(4242L), eq(900L));
    }

    @Test
    void mixedCheckedRowsComposeAStorageStateSnapshot() {
        stageSlot(5L, List.of(
                stagedCookie("sid", "s-value"),
                Map.of("kind", "local_storage", "name", "jwt", "origin", "https://a.example",
                        "value", "j-value")));
        String payload = """
                {"stagingToken":"tok-1","stateName":"混合账号",
                 "entries":[{"name":"sid","selected":true},
                            {"name":"jwt","selected":true}]}""";
        when(loginStateService.register(eq(77L), any(LoginStateInput.class))).thenReturn(7L);

        strategy.land(draftRow(payload), 1L);

        ArgumentCaptor<LoginStateInput> input = ArgumentCaptor.forClass(LoginStateInput.class);
        verify(loginStateService).register(eq(77L), input.capture());
        assertEquals(LoginStateEntriesComposer.LEDGER_KIND_STORAGE_STATE,
                input.getValue().kind());
        assertTrue(input.getValue().secret().contains("j-value"));
    }

    @Test
    void expiredSlotRefusesWithoutWriting() {
        when(store.load("tok-1")).thenReturn(null);
        String payload = """
                {"stagingToken":"tok-1","stateName":"账号",
                 "entries":[{"name":"sid","selected":true}]}""";

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow(payload), 1L));
        assertTrue(error.getMessage().contains("已过期"));
        verify(loginStateService, never()).register(anyLong(), any());
        verify(store, never()).remove(anyString());
    }

    @Test
    void slotOwnedByAnotherSessionIsRefused() {
        stageSlot(6L, List.of(stagedCookie("sid", "s-value")));
        String payload = """
                {"stagingToken":"tok-1","stateName":"账号",
                 "entries":[{"name":"sid","selected":true}]}""";

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow(payload), 1L));
        assertTrue(error.getMessage().contains("会话"));
        verify(loginStateService, never()).register(anyLong(), any());
    }

    @Test
    void emptyCheckListIsRefusedAsAMeaninglessAuthorization() {
        stageSlot(5L, List.of(stagedCookie("sid", "s-value")));
        String payload = """
                {"stagingToken":"tok-1","stateName":"账号",
                 "entries":[{"name":"sid","selected":false}]}""";

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow(payload), 1L));
        assertTrue(error.getMessage().contains("勾选"));
        verify(loginStateService, never()).register(anyLong(), any());
    }

    @Test
    void checkedRowAbsentFromTheSlotFailsTheWholeCardClosed() {
        stageSlot(5L, List.of(stagedCookie("sid", "s-value")));
        String payload = """
                {"stagingToken":"tok-1","stateName":"账号",
                 "entries":[{"name":"sid","selected":true},
                            {"name":"ghost","selected":true}]}""";

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow(payload), 1L));
        assertTrue(error.getMessage().contains("ghost"));
        // Fail-closed: nothing landed, and the slot stays for a corrected
        // re-confirm inside its window.
        verify(loginStateService, never()).register(anyLong(), any());
        verify(store, never()).remove(anyString());
    }

    @Test
    void missingTokenOrStateNameIsRefused() {
        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(draftRow("{\"entries\":[]}"), 1L));
        assertTrue(error.getMessage().contains("重新"));
        verify(loginStateService, never()).register(anyLong(), any());
    }

    @Test
    void captureEntryRecordKeepsItsShapeForTheComposer() {
        // Anchor for the staged row mapping: kind/name/origin/value/expires.
        CaptureCommittedEntry entry =
                new CaptureCommittedEntry("cookie", "sid", ".shop.example", "v", 1L);
        assertEquals("sid", entry.name());
    }
}
