package com.sixbean.testpike.core.aichat.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Issue-order sequence allocator for the draft ledger .
 * <p>Same-target pending cards renew automatically behind the confirmation
 * door, and the stale-exclusivity gate needs a deterministic "newest card"
 * that survives status churn (a voided or applied card keeps its issue slot
 * while ids keep growing from other drafts). {@code seq} is that stable
 * per-target issue order; this suite pins the allocator against the real
 * database so the MAX+1 semantics per target are proven, not mocked.
 * <p>Fixture posture (R37): rows are self-built under random target ids and
 * cleaned up by id afterwards; factory seed tables stay untouched.
 */
@SpringBootTest(classes = CoreApplication.class)
class DraftSeqAllocatorTest {

    @Autowired
    private DraftSeqAllocator allocator;

    @Autowired
    private NamedParameterJdbcTemplate named;

    @Autowired
    private JdbcTemplate jdbc;

    /** Draft ids minted by this test, deleted by id in cleanup. */
    private final List<Long> mintedIds = new ArrayList<>();

    /** A target id that cannot collide with any real entity row. */
    private long randomTarget() {
        return Math.abs(UUID.randomUUID().getMostSignificantBits()) + 9_000_000_000L;
    }

    /** A raw ledger row carrying the given issue seq on the given target. */
    private void insertDraftRow(long targetId, Integer seq) {
        Long id = named.queryForObject("""
                        INSERT INTO tp_ai_chat_draft
                            (session_id, message_id, object_type, target_id, seq,
                             payload_json, status)
                        VALUES (NULL, NULL, 'CASE_WRITE', :target, :seq,
                                '{}', 1)
                        RETURNING id
                        """,
                new MapSqlParameterSource()
                        .addValue("target", targetId)
                        .addValue("seq", seq),
                Long.class);
        mintedIds.add(id);
    }

    @Test
    void createStyleDraftWithoutTargetTakesNoIssueNumber() {
        // Create-style proposals have no target yet: they never enter the
        // same-target queue, so the allocator must not consult the ledger.
        assertNull(allocator.nextSeq(null));
    }

    @Test
    void firstDraftOnAFreshTargetStartsAtOne() {
        long target = randomTarget();
        assertEquals(1, allocator.nextSeq(target));
    }

    @Test
    void nextIssueNumberFollowsTheHighestSeqOfTheSameTarget() {
        long target = randomTarget();
        insertDraftRow(target, 1);
        insertDraftRow(target, 2);
        assertEquals(3, allocator.nextSeq(target));
    }

    @Test
    void numberingSkipsOverGapsLeftByVoidedCards() {
        long target = randomTarget();
        // A queue where the newest card (7) is voided still must not re-issue
        // 7: the allocator follows the maximum, never the count.
        insertDraftRow(target, 7);
        assertEquals(8, allocator.nextSeq(target));
    }

    @Test
    void separateTargetsNumberIndependently() {
        long targetA = randomTarget();
        long targetB = randomTarget();
        insertDraftRow(targetA, 1);
        insertDraftRow(targetA, 2);
        insertDraftRow(targetB, 1);
        assertEquals(3, allocator.nextSeq(targetA));
        assertEquals(2, allocator.nextSeq(targetB));
    }

    @org.junit.jupiter.api.AfterEach
    void cleanUpMintedRows() {
        for (Long id : mintedIds) {
            jdbc.update("DELETE FROM tp_ai_chat_draft WHERE id = ?", id);
        }
        mintedIds.clear();
    }
}
