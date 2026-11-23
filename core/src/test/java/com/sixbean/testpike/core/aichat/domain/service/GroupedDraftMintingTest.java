package com.sixbean.testpike.core.aichat.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Grouped draft minting : one assistant answer that
 * proposes several changes must mint one confirmable card per proposal, not
 * one per message. The old ledger welded idempotency to message_id, so the
 * second draft frame of the same turn was silently dropped by the landing
 * insert and pinned onto the FIRST card's id — two frames, one card, and the
 * human never sees the second proposal.
 * <p>What this suite pins on the real dev database, zero mocks:
 * <ul>
 * <li>two distinct proposals on one message mint two draft rows;</li>
 * <li>each DRAFT frame's pinned meta draftId is its OWN card id;</li>
 * <li>re-appending the very same proposal frame (emitter retry) mints no
 * second ticket and pins onto the existing card — the per-message
 * idempotency protection is kept at proposal granularity;</li>
 * <li>rows record the frame_seq that minted them (provenance for the
 * group-confirmation door built in the next piece).</li>
 * </ul>
 * Create-kind anchor shape (caseId 0) is used deliberately: no business rows
 * exist yet, so landing skips the live-version re-read and the suite touches
 * only the chat ledger.
 */
@SpringBootTest(classes = CoreApplication.class)
class GroupedDraftMintingTest {

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DraftLedgerCleanup cleanup;

    @Autowired
    private LedgerSessionSeed seed;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private LedgerSessionSeed.Scope scope;
    private long sessionId;
    private String tag;

    @BeforeEach
    void setUp() {
        // Scope seeding is single-sourced (R40): LedgerSessionSeed owns the
        // user/project/session shape every dialog suite needs.
        scope = seed.open("grpmint", "Grouped minting", "{\"route\":\"/cases\"}");
        sessionId = scope.sessionId();
        tag = scope.tag();
    }

    @AfterEach
    void tearDown() {
        cleanup.sessionChain(sessionId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", scope.projectId());
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", scope.userId());
    }

    /** CASE_WRITE create-kind meta: targetId stays 0, no optimistic anchor re-read. */
    private String createMeta(String title) throws Exception {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("objectType", "CASE_WRITE");
        meta.put("caseId", 0L);
        meta.put("baseVersion", 0);
        meta.put("model", "test-model");
        meta.put("actionJson", "{\"title\":\"" + title + "\",\"precondition\":\"seeded\","
                + "\"expectedResult\":\"home shown\",\"priority\":1,\"confidence\":2,"
                + "\"endType\":\"web\",\"phase\":2}");
        return objectMapper.writeValueAsString(meta);
    }

    private List<Long> draftIds(long messageId) {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_ai_chat_draft WHERE message_id = ? ORDER BY id",
                Long.class, messageId);
    }

    private Long pinnedDraftId(long seq) {
        return jdbcTemplate.queryForObject(
                "SELECT (meta_json->>'draftId')::bigint FROM tp_ai_chat_frame "
                        + "WHERE session_id = ? AND seq = ?",
                Long.class, sessionId, seq);
    }

    @Test
    void twoProposalsOnOneMessageMintTwoCardsEachFramePointingAtItsOwn() throws Exception {
        long message = frameService.openMessage(sessionId, "assistant", null);
        long seqA = frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                "case write proposal A", createMeta("grouped A " + tag));
        long seqB = frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                "case write proposal B", createMeta("grouped B " + tag));

        List<Long> ids = draftIds(message);
        assertEquals(2, ids.size(),
                "both proposals of one answer must become confirmable cards");
        Long pinnedA = pinnedDraftId(seqA);
        Long pinnedB = pinnedDraftId(seqB);
        assertEquals(ids.get(0), pinnedA, "first frame pins its own card");
        assertEquals(ids.get(1), pinnedB, "second frame pins its own card, not the first one");
        assertNotEquals(pinnedA, pinnedB);

        Long frameSeqA = jdbcTemplate.queryForObject(
                "SELECT frame_seq FROM tp_ai_chat_draft WHERE id = ?", Long.class, ids.get(0));
        assertEquals(seqA, frameSeqA, "draft row records the frame that minted it");
    }

    @Test
    void replayOfTheSameProposalFrameMintsNoSecondTicketAndPinsTheExistingCard() throws Exception {
        long message = frameService.openMessage(sessionId, "assistant", null);
        String meta = createMeta("grouped replay " + tag);
        long seqA = frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                "case write proposal", meta);
        long seqRetry = frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                "case write proposal", meta);

        List<Long> ids = draftIds(message);
        assertEquals(1, ids.size(),
                "an identical proposal re-appended to the same message never doubles the ticket");
        assertEquals(pinnedDraftId(seqA), pinnedDraftId(seqRetry),
                "the retry frame pins the existing card id");
        assertEquals(ids.get(0), pinnedDraftId(seqA));
    }
}
