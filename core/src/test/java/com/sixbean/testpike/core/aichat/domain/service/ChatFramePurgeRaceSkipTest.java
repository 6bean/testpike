package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.aichat.domain.component.ChatFrameType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Purge-race posture of the frame write door.
 *
 * <p>Why this suite exists: the live service log showed 72 first-frame
 * {@code tp_ai_chat_frame_message_id_fkey} violations, every one of them
 * raised by {@code appendFrame} inserting a frame onto a message row that a
 * concurrent project purge had already deleted (the purge is the only delete
 * channel for the message ledger). A streaming turn that outlives its
 * conversation must retire quietly — not raise one framework data-integrity
 * error per remaining frame.
 *
 * <p>What is pinned on the real dev database, zero mocks:
 * <ul>
 * <li>appending to a deleted message skips honestly: no exception, the
 * documented skip sentinel, no frame row, and no seq burned on the still
 * readable session (a burned seq would open a gap in the surviving
 * numbering);</li>
 * <li>a DRAFT append after the same purge lands no draft ticket — the skip
 * happens at the write door, upstream of every landing step;</li>
 * <li>the happy path is untouched: an append onto a live message still
 * allocates a positive seq, writes exactly one frame row and anchors
 * {@code seq_from} — the existence guard must never false-skip a live turn.</li>
 * </ul>
 */
@SpringBootTest(classes = CoreApplication.class)
class ChatFramePurgeRaceSkipTest {

    /** Skip sentinel documented on the interface: 0 means "the frame did not land". */
    private static final long SKIP_SENTINEL = 0L;

    @Autowired
    private ChatFrameService frameService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DraftLedgerCleanup cleanup;

    @Autowired
    private LedgerSessionSeed seed;

    private LedgerSessionSeed.Scope scope;
    private long sessionId;

    @BeforeEach
    void setUp() {
        // Scope seeding is single-sourced (R40): LedgerSessionSeed owns the
        // user/project/session shape every dialog suite needs.
        scope = seed.open("frprace", "Purge race", "{\"route\":\"/cases\"}");
        sessionId = scope.sessionId();
    }

    @AfterEach
    void tearDown() {
        cleanup.sessionChain(sessionId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", scope.projectId());
        jdbcTemplate.update("DELETE FROM tp_user WHERE id = ?", scope.userId());
    }

    /** Simulate the committed half of the observed race: the message row is gone. */
    private void purgeMessageRow(long messageId) {
        jdbcTemplate.update("DELETE FROM tp_ai_chat_message WHERE id = ?", messageId);
    }

    @Test
    void appendOntoDeletedMessageSkipsHonestlyWithoutBurningSeq() {
        long message = frameService.openMessage(sessionId, "assistant", null);
        long seqBefore = frameService.lastSeq(sessionId);
        purgeMessageRow(message);

        long landed = frameService.appendFrame(sessionId, message, ChatFrameType.DONE,
                "tail of a turn whose conversation was purged", "{}");

        assertEquals(SKIP_SENTINEL, landed,
                "an append racing a committed purge skips honestly instead of raising the FK violation");
        Integer frames = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_frame WHERE session_id = ? AND message_id = ?",
                Integer.class, sessionId, message);
        assertEquals(0, frames, "the skipped append must not leave a frame row behind");
        assertEquals(seqBefore, frameService.lastSeq(sessionId),
                "a skipped append never burns a seq, so the surviving numbering keeps no gap");
    }

    @Test
    void draftAppendOntoDeletedMessageLandsNoTicket() {
        long message = frameService.openMessage(sessionId, "assistant", null);
        purgeMessageRow(message);

        long landed = frameService.appendFrame(sessionId, message, ChatFrameType.DRAFT,
                "case write proposal after purge", "{\"objectType\":\"CASE_WRITE\"}");

        assertEquals(SKIP_SENTINEL, landed,
                "the skip happens at the write door, upstream of the draft landing step");
        Integer drafts = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_draft WHERE message_id = ?",
                Integer.class, message);
        assertEquals(0, drafts, "a purged conversation mints no confirmable card");
    }

    @Test
    void appendOntoLiveMessageStillAllocatesSeqAndWritesOneFrame() {
        long message = frameService.openMessage(sessionId, "assistant", null);
        long seqBefore = frameService.lastSeq(sessionId);

        long landed = frameService.appendFrame(sessionId, message, ChatFrameType.DELTA,
                "streamed text while the conversation lives", "{}");

        assertTrue(landed > SKIP_SENTINEL, "a live turn always lands");
        assertNotEquals(seqBefore, frameService.lastSeq(sessionId),
                "the session cursor advances exactly when the frame lands");
        Integer frames = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_chat_frame WHERE session_id = ? AND seq = ?",
                Integer.class, sessionId, landed);
        assertEquals(1, frames, "the frame row is written under the returned seq");
        Long seqFrom = jdbcTemplate.queryForObject(
                "SELECT seq_from FROM tp_ai_chat_message WHERE id = ?", Long.class, message);
        assertEquals(landed, seqFrom, "the first frame still anchors the message window");
    }
}
