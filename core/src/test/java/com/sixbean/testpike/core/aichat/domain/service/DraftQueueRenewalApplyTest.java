package com.sixbean.testpike.core.aichat.domain.service;


import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.api.dto.DraftTicket;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Auto-renewal of the same-target pending draft queue behind the
 * confirmation door .
 * <p>When the human confirms one card of a target, every still-pending
 * sibling of that target is re-planned automatically through
 * {@code DraftQueueRenewalPolicy}: disjoint proposals re-anchor onto the
 * freshly landed version (no manual "renew" click), proposals that would
 * collide with what just landed turn into the stale one-click-regenerate
 * state, and the exclusivity gate keeps at most one stale card per target
 * (older losers are voided with a reason naming the surviving card).
 * A sibling whose anchor never moved stays byte-identical — idempotent,
 * zero writes.
 * <p>Real chain, zero mocks: cards are minted through the shared frame
 * ledger (so the issue-order seq is the production one) and confirmed
 * through the generic door with a real one-shot ticket.
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftQueueRenewalApplyTest {

    @Autowired
    private DraftConfirmationService confirmationService;

    @Autowired
    private ChatSessionService sessionService;

    @Autowired
    private DraftCardMinting minting;

    @Autowired
    private DraftLedgerCleanup cleanup;

    @Autowired
    private CaseService caseService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private NamedParameterJdbcTemplate named;

    private String tag;
    private long projectId;
    private long userId;
    private long sessionId;
    private final List<Long> createdCaseIds = new ArrayList<>();
    private final List<Long> rawDraftIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        tag = "t486q" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        userId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        projectId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, tag, tag);
        ChatSessionInfo session = sessionService.create(projectId, userId, "Queue renewal " + tag,
                1, "{\"route\":\"/cases\"}");
        sessionId = session.id();
    }

    @AfterEach
    void tearDown() {
        for (long id : rawDraftIds) {
            jdbcTemplate.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", id);
        }
        cleanup.sessionChain(sessionId);
        cleanup.caseCascade(createdCaseIds);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", userId);
    }

    private long seedCase(String title) {
        CaseInfo created = caseService.create(userId, new CaseCreateInput(projectId, title,
                "pre", "expect", 1, 2, "web", null, 2, 1, null, List.of()));
        createdCaseIds.add(created.id());
        return created.id();
    }

    /** Mint a CASE_WRITE edit card through the shared frame ledger (real seq). */
    private long landCaseWriteDraft(long caseId, int baseVersion, String payloadJson) {
        return minting.caseWriteDraft(sessionId, caseId, baseVersion, payloadJson);
    }

    /** Raw ledger row for the shapes the emitter cannot produce yet. */
    private long insertRawDraft(long targetId, int seq, int baseVersion, String objectType,
                                String payloadJson) {
        Long id = named.queryForObject("""
                        INSERT INTO tp_ai_chat_draft
                            (session_id, message_id, object_type, target_id, seq,
                             base_version, payload_json, status)
                        VALUES (NULL, NULL, :type, :target, :seq, :base,
                                CAST(:payload AS jsonb), 1)
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("type", objectType)
                        .addValue("target", targetId)
                        .addValue("seq", seq)
                        .addValue("base", baseVersion)
                        .addValue("payload", payloadJson),
                Long.class);
        assertNotNull(id);
        rawDraftIds.add(id);
        return id;
    }

    private Map<String, Object> draftRow(long draftId) {
        return jdbcTemplate.queryForMap(
                "SELECT status, base_version, seq, status_note, updated_at FROM tp_ai_chat_draft WHERE id = ?",
                draftId);
    }

    private void confirm(long draftId) {
        DraftTicket ticket = confirmationService.issueConfirmToken(userId, draftId);
        confirmationService.apply(userId, draftId, ticket.token());
    }

    @Test
    void disjointEditSiblingAutoReanchorsWithoutAnyHumanClick() {
        long caseId = seedCase("renewal disjoint");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1, "{\"precondition\":\"p from card 1\"}");
        long card2 = landCaseWriteDraft(caseId, v1, "{\"expectedResult\":\"e from card 2\"}");

        confirm(card1);

        int landed = caseService.get(caseId).version();
        assertEquals(v1 + 1, landed);
        Map<String, Object> row = draftRow(card2);
        assertEquals(1, ((Number) row.get("status")).intValue(),
                "a disjoint proposal stays pending — renewal is not a write");
        assertEquals(landed, ((Number) row.get("base_version")).intValue(),
                "the anchor moved onto the freshly landed version by itself");
    }

    @Test
    void collidingEditSiblingTurnsStaleWithAPlainWordReason() {
        long caseId = seedCase("renewal collide");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1, "{\"title\":\"landed by card 1\"}");
        long card2 = landCaseWriteDraft(caseId, v1, "{\"title\":\"would overwrite card 1\"}");

        confirm(card1);

        int landed = caseService.get(caseId).version();
        Map<String, Object> row = draftRow(card2);
        assertEquals(4, ((Number) row.get("status")).intValue(),
                "a proposal that would collide turns into the stale state");
        String note = (String) row.get("status_note");
        assertNotNull(note, "the stale state carries a plain reason");
        assertTrue(note.contains(String.valueOf(landed)) && note.contains(String.valueOf(v1)),
                "the reason names both versions: " + note);
        assertTrue(note.contains("重新生成"), "the reason points at the one-click exit: " + note);
        assertEquals("landed by card 1", caseService.get(caseId).title(),
                "the stale sibling wrote nothing");
    }

    @Test
    void staleExclusivityKeepsTheNewestCardAndVoidsOlderLosersNamingIt() {
        long caseId = seedCase("renewal exclusivity");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1,
                "{\"title\":\"A\",\"precondition\":\"P\",\"expectedResult\":\"E\"}");
        long card2 = landCaseWriteDraft(caseId, v1, "{\"title\":\"B\"}");
        long card3 = landCaseWriteDraft(caseId, v1, "{\"precondition\":\"Q\"}");

        confirm(card1);

        Map<String, Object> older = draftRow(card2);
        Map<String, Object> newest = draftRow(card3);
        assertEquals(3, ((Number) older.get("status")).intValue(),
                "the older loser is voided — at most one stale card per target");
        assertEquals(4, ((Number) newest.get("status")).intValue(),
                "the newest by issue order keeps an actionable stale card");
        String voidReason = (String) older.get("status_note");
        assertNotNull(voidReason);
        assertTrue(voidReason.contains("卡 #" + card3),
                "the void reason names the surviving card: " + voidReason);
    }

    @Test
    void siblingAlreadyOnTheLandedVersionStaysByteIdentical() {
        long caseId = seedCase("renewal untouched");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1, "{\"title\":\"moves to v2\"}");
        // A queue row that already sits on the version card1 is about to land:
        // renewal must plan zero writes for it (idempotent shape).
        long sibling = insertRawDraft(caseId, 2, v1 + 1, "CASE_WRITE", "{}");
        Map<String, Object> before = draftRow(sibling);

        confirm(card1);

        Map<String, Object> after = draftRow(sibling);
        assertEquals(before.get("status"), after.get("status"));
        assertEquals(before.get("base_version"), after.get("base_version"));
        assertEquals(before.get("updated_at"), after.get("updated_at"),
                "an anchor that did not move is not rewritten at all");
        assertNull(after.get("status_note"));
    }

    @Test
    void quarantineSiblingOnAMovedCaseRebasesInsteadOfGoingStale() {
        long caseId = seedCase("renewal quarantine sibling");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1, "{\"title\":\"edit lands first\"}");
        // A pending quarantine card on the same case: isolation replays onto
        // any version safely (idempotent refresh), so it re-anchors, never stales.
        long flaky = insertRawDraft(caseId, 2, v1, "FLAKY_ISOLATION", "{\"reason\":\"flaky r1\"}");

        confirm(card1);

        int landed = caseService.get(caseId).version();
        Map<String, Object> row = draftRow(flaky);
        assertEquals(1, ((Number) row.get("status")).intValue());
        assertEquals(landed, ((Number) row.get("base_version")).intValue(),
                "the quarantine proposal re-anchors onto the moved case");
    }

    @Test
    void crossFamilySiblingWithoutAReaderTurnsStaleConservatively() {
        long caseId = seedCase("renewal unknown reader");
        int v1 = caseService.get(caseId).version();
        long card1 = landCaseWriteDraft(caseId, v1, "{\"title\":\"case edit lands\"}");
        // A row naming another family on this target: no version reader can
        // reconstruct its baseline — refuse to guess, turn it stale.
        long foreign = insertRawDraft(caseId, 2, v1, "MOCK_STUB_WRITE", "{\"name\":\"n\"}");

        confirm(card1);

        Map<String, Object> row = draftRow(foreign);
        assertEquals(4, ((Number) row.get("status")).intValue(),
                "unjudgeable baseline => conservative stale, never a silent pass");
        assertNotNull(row.get("updated_at"));
    }
}
