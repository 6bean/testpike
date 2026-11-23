package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftApplyResult;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.script.api.dto.ScriptInfo;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Repair-draft confirmation chain : the only
 * door through which a dialog repair proposal may reach the script ledger.
 * <p>Red line pinned by every case: proposals never self-apply — a script
 * write requires an issued confirm token (Redis, one-shot GETDEL) plus a
 * base_version match against the live script. The refusal spectrum the
 * confirmation door must hold: no token / forged or expired token / token
 * replay / foreign user (horizontal access) / optimistic-lock conflict.
 * Guard order is fixed: draft exists (40401) → session belongs to the caller
 * (40301) → still pending (40904) → token consumed (40001) → version match
 * (40901). Landing itself is exercised by DraftConfirmationLandingTest —
 * this suite drives drafts through that same real landing path, zero mocks,
 * real dev database (same posture as ChatFrameProtocolTest).
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftConfirmationChainTest {

    /** Base script the proposal is cut against (same shape the healing suite uses). */
    private static final String BASE_SCRIPT =
            "{\"version\":\"v2\",\"steps\":["
                    + "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"resourceId\":\"com.demo:id/old\"}}]}";

    /** Proposed repair: the stale anchor swapped for a content-desc locator. */
    private static final String REPAIRED_SCRIPT =
            "{\"version\":\"v2\",\"steps\":["
                    + "{\"action\":\"app.launch\",\"target\":{\"pkg\":\"com.demo\"}},"
                    + "{\"action\":\"app.tap\",\"target\":{\"content-desc\":\"login\"}}]}";

    private static final String TOKEN_KEY_PREFIX = "aichat:draft:confirm:";

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private String tag;
    private long projectId;
    private long userId;
    private long caseId;
    private long sessionId;
    /**
     * Audit ledger high-water at setUp: draft ids come from a sequence that a
     * schema re-provision can rewind (tp_ai_chat_draft id reuse is real — the
     * append-only audit log still holds yesterday's rows under the same id),
     * so every per-run audit count is windowed to rows created after this id.
     */
    private long auditBaselineId;

    @BeforeEach
    void setUp() {
        auditBaselineId = jdbcTemplate.queryForObject(
                "SELECT COALESCE(max(id), 0) FROM tp_audit_log", Long.class);
        tag = "t429c" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        caseId = caseService.create(userId, new CaseCreateInput(projectId, "repair draft case",
                "pre", "expect", 1, 2, "mobile", 3, 2, 1, null, List.of(), null, null,
                "app", "MOBILEWRIGHT", null)).id();
        scriptService.save(new ScriptSaveInput(caseId, "v2", BASE_SCRIPT, 2));
        ChatSessionInfo session = sessionService.create(projectId, userId, "Confirm chain " + tag,
                1, "{\"route\":\"/cases\"}");
        sessionId = session.id();
    }

    @AfterEach
    void tearDown() {
        jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_frame WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE session_id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_ai_chat_session WHERE id = ?", sessionId);
        jdbcTemplate.update("DELETE FROM tp_script_version WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_script WHERE case_id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_test_case WHERE id = ?", caseId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    /** Land a confirmable draft through the real frame ledger path). */
    private long landDraft() {
        int baseVersion = currentScript().version();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("executionId", 9001);
        meta.put("caseId", caseId);
        meta.put("baseVersion", baseVersion);
        meta.put("schemaVersion", "v2");
        meta.put("model", "test-model");
        meta.put("actionJson", REPAIRED_SCRIPT);
        long message = frameService.openMessage(sessionId, "assistant", null);
        try {
            frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                    "swap stale locator", objectMapper.writeValueAsString(meta));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = ?", Long.class, message);
    }

    private ScriptInfo currentScript() {
        ScriptInfo script = scriptService.getByCaseId(caseId);
        assertNotNull(script);
        return script;
    }

    private int draftStatus(long draftId) {
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_chat_draft WHERE id = ?", Integer.class, draftId);
        assertNotNull(status);
        return status;
    }

    @Test
    void issuedTokenApplyWritesScriptOnceAndFlipsDraftToApplied() {
        long draftId = landDraft();
        int baseVersion = currentScript().version();

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        assertNotNull(ticket.token());
        assertTrue(ticket.expiresInSeconds() > 0, "ticket echoes the live TTL window");
        assertEquals(1, auditCount("draft-confirm-issue", draftId),
                "issuing the door is itself auditable (who opened it)");

        DraftApplyResult result = confirmationService.apply(userId, draftId, ticket.token());
        assertEquals(caseId, result.targetId());
        ScriptInfo after = currentScript();
        assertEquals(baseVersion + 1, after.version(), "applying writes exactly one new version");
        assertEquals(1, after.generatedBy(), "the repair line stays attributed to AI");
        assertTrue(after.actionJson().contains("content-desc"), "the proposed JSON landed");
        assertEquals(2, draftStatus(draftId), "applied drafts leave the pending state");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT applied_version, applied_by FROM tp_ai_chat_draft WHERE id = ?",
                draftId);
        assertEquals(baseVersion + 1, ((Number) row.get("applied_version")).intValue());
        assertEquals(userId, ((Number) row.get("applied_by")).longValue());
        assertEquals(1, auditCount("draft-confirm-apply", draftId));

        // The confirmed write is a real human verdict: exactly one draft-channel
        // row lands in the behavior outcome ledger .
        assertEquals(1, jdbcTemplate.queryForObject(
                        "SELECT count(*) FROM tp_ai_behavior_outcome"
                                + " WHERE source = 'draft' AND source_event_id = ?",
                        Integer.class, "draft-apply:" + draftId),
                "applying a draft through the confirmation door counts once in the ledger");
        jdbcTemplate.update("DELETE FROM tp_ai_behavior_outcome WHERE source = 'draft' AND source_event_id = ?",
                "draft-apply:" + draftId);
    }

    @Test
    void applyWithoutAnyTokenIsRefusedAndNothingMoves() {
        long draftId = landDraft();
        int baseVersion = currentScript().version();

        ServiceException e = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertEquals(baseVersion, currentScript().version(), "a refused apply never touches the script");
        assertEquals(1, draftStatus(draftId), "the draft stays confirmable after a bare refusal");
    }

    @Test
    void forgedOrExpiredTokenIsRefusedLikeTheMissingOne() {
        long draftId = landDraft();
        int baseVersion = currentScript().version();

        ServiceException forged = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, "f".repeat(64)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, forged.getCode());

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        redis.delete(TOKEN_KEY_PREFIX + ticket.token()); // expire = the key walking away
        ServiceException expired = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, expired.getCode());
        assertEquals(baseVersion, currentScript().version());
        assertEquals(1, draftStatus(draftId));
    }

    @Test
    void aConsumedTokenCannotBeReplayedAgainstAnotherDraft() {
        long first = landDraft();
        long second = landDraft();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, first);
        confirmationService.apply(userId, first, ticket.token());

        // The token is gone (GETDEL) AND the draft is applied; re-targeting the
        // same random string at a sibling draft must be a human refusal, not a write.
        int beforeSecond = currentScript().version();
        ServiceException replay = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, second, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, replay.getCode());
        assertEquals(beforeSecond, currentScript().version());
        assertEquals(1, draftStatus(second), "the untouched draft is still pending");
    }

    @Test
    void appliedDraftRefusesAnotherConfirmation() {
        long draftId = landDraft();
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        confirmationService.apply(userId, draftId, ticket.token());

        ServiceException again = assertThrows(ServiceException.class,
                () -> confirmationService.issueConfirmToken(userId, draftId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, again.getCode());
    }

    @Test
    void foreignUserIsRefusedBeforeAnyTicketOrWrite() {
        long draftId = landDraft();
        long stranger = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag + "x", tag + "x");
        try {
            ServiceException issue = assertThrows(ServiceException.class,
                    () -> confirmationService.issueConfirmToken(stranger, draftId));
            assertEquals(BizErrorCode.FORBIDDEN, issue.getCode());

            DraftTicket owner = confirmationService.issueConfirmToken(userId, draftId);
            ServiceException apply = assertThrows(ServiceException.class,
                    () -> confirmationService.apply(stranger, draftId, owner.token()));
            assertEquals(BizErrorCode.FORBIDDEN, apply.getCode());
            assertEquals(1, draftStatus(draftId), "the refused apply leaves the draft confirmable");
        } finally {
            jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", stranger);
        }
    }

    @Test
    void optimisticLockConflictRefusesAndBurnsTheTicket() {
        long draftId = landDraft();
        // Someone edits the script after the proposal was cut (version moves on).
        scriptService.save(new ScriptSaveInput(caseId, "v2", BASE_SCRIPT.replace("id/old", "id/mid"), 2));
        int movedVersion = currentScript().version();

        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        ServiceException conflict = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.VERSION_CONFLICT, conflict.getCode());
        assertEquals(movedVersion, currentScript().version(), "a stale proposal writes nothing");
        assertEquals(1, draftStatus(draftId), "the draft survives to be regenerated against the new version");

        // fail-closed: the burned ticket cannot be retried — a fresh confirmation is needed.
        ServiceException burned = assertThrows(ServiceException.class,
                () -> confirmationService.apply(userId, draftId, ticket.token()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, burned.getCode());
    }

    private int auditCount(String action, long draftId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = ? AND object_id = ? AND id > ?",
                Integer.class, action, String.valueOf(draftId), auditBaselineId);
        return n == null ? 0 : n;
    }
}
