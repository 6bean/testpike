package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.aichat.domain.service.DraftWriteStrategy;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.api.dto.MockStubInput;
import com.sixbean.testpike.core.mock.domain.service.MockSpaceService;
import com.sixbean.testpike.core.mock.domain.service.MockStubService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Single-key force exit for stale-anchor refusals .
 * <p>Since the card-time anchor is re-read server-side, so a
 * version mismatch caught at landing time is a REAL race between the human
 * seeing the card and confirming it — never the model echoing an old number.
 * The refusal wording therefore drops the "refresh and re-run the analysis"
 * dead-end and matches the case-write gate baseline: it names both versions
 * the proposal onto the current row. Covers the three kinds whose landing
 * still refused blanket-style: flaky quarantine, mock-rule edit and script
 * repair. Collaborators are mocked to isolate the door decision; the shared
 * write chains themselves are covered by their own domain suites, and the
 * live chain proof runs as the TC-ITR-01 L3 probe at segment close.
 */
class StaleDraftForceExitTest {

    // ---- flaky quarantine --------------------------------------------------

    @Test
    void staleQuarantineRefusalNamesVersionsAndOffersTheForceChoice() {
        FlakyIsolationDraftStrategy strategy = flakyStrategy(caseMovedTo(5));

        ServiceException error = assertThrows(ServiceException.class, () -> strategy.land(
                quarantineDraft(3, "{\"reason\":\"r\"}"), 9L));
        assertGateWording(error.getMessage(), "用例已被他人修改");
    }

    @Test
    void forceChoiceIsolatesEvenThoughTheCaseMoved() {
        CaseService caseService = mock(CaseService.class);
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.findById(7L)).thenReturn(session(7L, 100L));
        when(caseService.get(42L)).thenReturn(caseInfo(42L, 100L, 5));
        when(caseService.setFlaky(9L, 42L, true, "r")).thenReturn(caseInfo(42L, 100L, 6));
        FlakyIsolationDraftStrategy strategy =
                new FlakyIsolationDraftStrategy(caseService, sessionService);

        Integer applied = strategy.land(quarantineDraft(3, "{\"reason\":\"r\"}"),
                9L, DraftWriteStrategy.ACTION_FORCE);

        assertEquals(6, applied, "force replays the isolation onto the moved case");
        verify(caseService).setFlaky(9L, 42L, true, "r");
    }

    // ---- mock rule edit ------------------------------------------------------

    @Test
    void staleRuleEditRefusalNamesVersionsAndOffersTheForceChoice() {
        MockStubWriteDraftStrategy strategy = mockStrategy(stubAtVersion(5), 100L);

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(editDraft(3, "{\"name\":\"renamed by AI\"}"), 9L));
        assertGateWording(error.getMessage(), "规则已被他人修改");
    }

    @Test
    void forceChoiceReplaysTheRuleEditOnTheCurrentVersion() {
        MockStubService stubService = mock(MockStubService.class);
        when(stubService.get(42L)).thenReturn(stubAtVersion(5));
        when(stubService.update(eq(42L), any(MockStubInput.class))).thenReturn(stubAtVersion(6));
        MockStubWriteDraftStrategy strategy = new MockStubWriteDraftStrategy(
                stubService, spaceService(100L), new com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor(
                        sessionService(100L)), mock(JdbcTemplate.class));

        Integer applied = strategy.land(editDraft(3, "{\"name\":\"renamed by AI\"}"),
                9L, DraftWriteStrategy.ACTION_FORCE);

        assertEquals(6, applied, "force lands the edit and reports the new row version");
        ArgumentCaptor<MockStubInput> input = ArgumentCaptor.forClass(MockStubInput.class);
        verify(stubService).update(eq(42L), input.capture());
        assertEquals(5, input.getValue().expectedVersion(),
                "force replays the proposal against the LIVE row version, not the stale echo");
        assertEquals("renamed by AI", input.getValue().name());
    }

    // ---- script repair -------------------------------------------------------

    @Test
    void staleRepairRefusalNamesVersionsAndOffersTheForceChoice() {
        RepairDraftWriteStrategy strategy = repairStrategy(scriptAtVersion(5));

        ServiceException error = assertThrows(ServiceException.class,
                () -> strategy.land(repairDraft(3), 9L));
        assertGateWording(error.getMessage(), "脚本已被他人修改");
    }

    @Test
    void forceChoiceLandsTheRepairOverTheMovedScript() {
        ScriptService scriptService = mock(ScriptService.class);
        when(scriptService.getByCaseId(42L)).thenReturn(scriptAtVersion(5));
        when(scriptService.saveRepaired(42L, "v2", "{\"steps\":[]}"))
                .thenReturn(scriptAtVersion(6));
        RepairDraftWriteStrategy strategy = new RepairDraftWriteStrategy(scriptService);

        Integer applied = strategy.land(repairDraft(3), 9L, DraftWriteStrategy.ACTION_FORCE);

        assertEquals(6, applied, "force lands the repaired script as a new version");
    }

    // ---- helpers -------------------------------------------------------------

    /** Refusal wording must match the case-write gate baseline: object named,
     *  both versions named, force exit offered, refresh dead-end removed. */
    private static void assertGateWording(String message, String expectedHead) {
        assertTrue(message.contains(expectedHead), "must name what moved: " + message);
        assertTrue(message.contains("草案基于版本 3"), "must echo the draft anchor: " + message);
        assertTrue(message.contains("当前版本 5"), "must echo the live version: " + message);
        assertTrue(message.contains("以本次为准"), "must offer the one-key override: " + message);
        assertFalse(message.contains("刷新"), "refresh dead-end wording is retired: " + message);
        assertFalse(message.contains("重新让 AI 分析"),
                "re-analyse dead-end wording is retired: " + message);
    }

    private static FlakyIsolationDraftStrategy flakyStrategy(CaseInfo moved) {
        CaseService caseService = mock(CaseService.class);
        when(caseService.get(42L)).thenReturn(moved);
        return new FlakyIsolationDraftStrategy(caseService, sessionService(100L));
    }

    private static MockStubWriteDraftStrategy mockStrategy(MockStubInfo current, long spaceProject) {
        MockStubService stubService = mock(MockStubService.class);
        when(stubService.get(42L)).thenReturn(current);
        return new MockStubWriteDraftStrategy(stubService, spaceService(spaceProject),
                new com.sixbean.testpike.core.aichat.domain.component.DraftProjectAnchor(
                        sessionService(100L)), mock(JdbcTemplate.class));
    }

    private static RepairDraftWriteStrategy repairStrategy(ScriptInfo current) {
        ScriptService scriptService = mock(ScriptService.class);
        when(scriptService.getByCaseId(42L)).thenReturn(current);
        return new RepairDraftWriteStrategy(scriptService);
    }

    private static ChatSessionService sessionService(long projectId) {
        ChatSessionService sessionService = mock(ChatSessionService.class);
        when(sessionService.findById(7L)).thenReturn(session(7L, projectId));
        return sessionService;
    }

    private static MockSpaceService spaceService(long projectId) {
        MockSpaceService spaceService = mock(MockSpaceService.class);
        when(spaceService.get(9L)).thenReturn(new MockSpaceInfo(9L, projectId, "space", "key",
                null, null, null, 1, null, null, null, null, null, null,
                null, null, 1, LocalDateTime.now(), LocalDateTime.now(), 0, null));
        return spaceService;
    }

    private static ChatSessionInfo session(long id, long projectId) {
        return mock(ChatSessionInfo.class, inv -> "projectId".equals(inv.getMethod().getName())
                ? projectId : null);
    }

    private static CaseInfo caseInfo(long id, long projectId, int version) {
        return mock(CaseInfo.class, inv -> switch (inv.getMethod().getName()) {
            case "id" -> id;
            case "projectId" -> projectId;
            case "version" -> version;
            default -> null;
        });
    }

    private static CaseInfo caseMovedTo(int version) {
        return caseInfo(42L, 100L, version);
    }

    private static MockStubInfo stubAtVersion(int version) {
        return new MockStubInfo(42L, 9L, "rule", 100, "GET", "exact", "/p", null, null,
                null, null, null, "manual", null, true, version,
                LocalDateTime.now(), LocalDateTime.now());
    }

    private static ScriptInfo scriptAtVersion(int version) {
        return new ScriptInfo(3L, 42L, "v2", "{}", 1, 1, null, version, null, null);
    }

    private static Map<String, Object> quarantineDraft(int baseVersion, String payload) {
        return draftRow("FLAKY_ISOLATION", 42L, baseVersion, payload, null);
    }

    private static Map<String, Object> editDraft(int baseVersion, String payload) {
        return draftRow("MOCK_STUB_WRITE", 42L, baseVersion, payload, null);
    }

    private static Map<String, Object> repairDraft(int baseVersion) {
        return draftRow("SCRIPT_REPAIR", 42L, baseVersion, "{\"steps\":[]}", "v2");
    }

    private static Map<String, Object> draftRow(String kind, long targetId, int baseVersion,
                                                String payload, String schemaVersion) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", 501L);
        row.put("session_id", 7L);
        row.put("object_type", kind);
        row.put("target_id", targetId);
        row.put("base_version", baseVersion);
        row.put("payload_json", payload);
        row.put("schema_version", schemaVersion);
        return row;
    }
}
