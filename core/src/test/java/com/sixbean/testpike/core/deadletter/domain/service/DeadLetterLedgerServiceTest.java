package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Real dev database, no mocks (strict-testing iron law). Covers the two L2 obligations
 * named on the WBS row: the full classification vocabulary is exercised by
 * {@code DeadLetterFailureClassifierTest} ; this class covers booking an already
 * classified message into tp_mq_dead_letter and the duplicate-booking idempotency
 * (same message recorded twice = exactly one row), which is the guard the DLQ listeners
 * rely on when a message is re-delivered or dead-lettered again.
 * <p>Fixture discipline: rows are claimed through a run-unique fingerprint prefix and are
 * deleted by that fingerprint in {@code @AfterEach} — never TRUNCATE on a factory table (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class DeadLetterLedgerServiceTest {

    @Autowired
    private DeadLetterLedgerService ledgerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String fingerprintPrefix = "t382-" + UUID.randomUUID();

    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                fingerprintPrefix + "%");
    }

    private DeadLetterLedgerInput input(String suffix, DeadLetterFailureClass failureClass) {
        return new DeadLetterLedgerInput(
                fingerprintPrefix + "-" + suffix,
                "ai.generation.dlq",
                "tp.events",
                "ai.generation",
                "{\"caseId\":42}",
                failureClass,
                "下游供应商连接被重置",
                "{\"reason\":\"rejected\",\"queue\":\"ai.generation.dlq\"}");
    }

    @Test
    void blankFingerprintIsRejectedFailClosed() {
        DeadLetterLedgerInput noFingerprint = new DeadLetterLedgerInput(" ",
                "ai.generation.dlq", null, null, "{}",
                DeadLetterFailureClass.UNKNOWN, null, null);
        assertThrows(ServiceException.class, () -> ledgerService.record(noFingerprint));

        DeadLetterLedgerInput noQueue = new DeadLetterLedgerInput(fingerprintPrefix + "-nq",
                "  ", null, null, "{}",
                DeadLetterFailureClass.UNKNOWN, null, null);
        assertThrows(ServiceException.class, () -> ledgerService.record(noQueue));
    }

    @Test
    void bookedRowCarriesQueuePayloadClassificationAndPendingStatus() {
        Optional<Long> booked = ledgerService.record(input("a", DeadLetterFailureClass.PROVIDER_TRANSIENT));
        assertTrue(booked.isPresent(), "first booking must return the new ledger row id");

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT * FROM tp_mq_dead_letter WHERE message_fingerprint = ?",
                fingerprintPrefix + "-a");
        assertEquals(1, rows.size());
        Map<String, Object> row = rows.get(0);
        assertEquals("ai.generation.dlq", row.get("queue_name"));
        assertEquals("tp.events", row.get("orig_exchange"));
        assertEquals("ai.generation", row.get("orig_routing_key"));
        assertEquals("{\"caseId\":42}", row.get("payload_json"));
        assertEquals("PROVIDER_TRANSIENT", row.get("failure_class"));
        assertEquals("下游供应商连接被重置", row.get("failure_reason"));
        assertEquals("{\"reason\":\"rejected\",\"queue\":\"ai.generation.dlq\"}", row.get("x_death_json"));
        assertEquals("pending", row.get("status"));
        assertEquals(0, ((Number) row.get("replay_round")).intValue());
    }

    @Test
    void sameMessageBookedTwiceYieldsExactlyOneRow() {
        DeadLetterLedgerInput first = input("dup", DeadLetterFailureClass.ENTITY_GONE);
        Optional<Long> booked = ledgerService.record(first);
        assertTrue(booked.isPresent());

        // Redelivery of the identical message: idempotent — no second row, empty result.
        Optional<Long> replayed = ledgerService.record(input("dup", DeadLetterFailureClass.ENTITY_GONE));
        assertTrue(replayed.isEmpty(), "duplicate booking must not report a new row");

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE message_fingerprint = ?",
                Integer.class, fingerprintPrefix + "-dup");
        assertEquals(1, count, "same message must never produce two ledger rows");
    }

    @Test
    void redyingAfterReplayFlipsTheRowBackToPendingForTheRoundBudget() {
        // Auto-replay loop physical channel (adjudication O3-a-1): a reposted message that
        // dies AGAIN must be recorded on the SAME row — status back to pending with the
        // accumulated replay_round — so the loop can replay once more and finally void it.
        // The old DO NOTHING swallowed the second death: the row froze in "replayed" and
        // the round budget never closed (L4 window l4-dlq-auto-replay leg D, 2026-11-06).
        Optional<Long> booked = ledgerService.record(input("cycle", DeadLetterFailureClass.PROVIDER_TRANSIENT));
        assertTrue(booked.isPresent());
        Long rowId = booked.get();

        // Simulate the replay claim flip performed by DeadLetterReplayServiceImpl.
        jdbcTemplate.update("UPDATE tp_mq_dead_letter SET status = 'replayed', replay_round = 1,"
                + " operator = 'system-replay-auto-replay', updated_at = now() WHERE id = ?", rowId);

        Optional<Long> rebooked = ledgerService.record(new DeadLetterLedgerInput(
                fingerprintPrefix + "-cycle", "ai.generation.dlq", "tp.events", "ai.generation",
                "{\"caseId\":42}", DeadLetterFailureClass.PROVIDER_TRANSIENT,
                "补投后再次失败：供应商仍不可达", "{\"reason\":\"rejected\",\"queue\":\"ai.generation.dlq\"}"));
        assertTrue(rebooked.isPresent(), "a re-death of a replayed row is a new operational fact");
        assertEquals(rowId, rebooked.get(), "the cycle must stay on the same ledger row (no second row)");

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT * FROM tp_mq_dead_letter WHERE id = ?", rowId);
        assertEquals("pending", row.get("status"), "re-death returns the row to pending for the next round");
        assertEquals(1, ((Number) row.get("replay_round")).intValue(), "round accumulation is kept");
        assertEquals("补投后再次失败：供应商仍不可达", row.get("failure_reason"));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE message_fingerprint = ?",
                Integer.class, fingerprintPrefix + "-cycle").intValue(),
                "round cycling never forks a second row for the same message");
    }

    @Test
    void redyingAVoidedRowStaysVoidedAndReportsNothing() {
        // Terminal semantics: a voided row is a closed case. A stray re-death must not
        // resurrect it into pending without an explicit adjudicated action.
        Optional<Long> booked = ledgerService.record(input("void", DeadLetterFailureClass.CONFIG_MISSING));
        assertTrue(booked.isPresent());
        jdbcTemplate.update("UPDATE tp_mq_dead_letter SET status = 'voided',"
                + " operator = 'system-replay-auto-void', updated_at = now() WHERE id = ?", booked.get());

        Optional<Long> rebooked = ledgerService.record(input("void", DeadLetterFailureClass.CONFIG_MISSING));
        assertTrue(rebooked.isEmpty(), "a voided row must not be re-opened by a duplicate death booking");
        assertEquals("voided", jdbcTemplate.queryForObject(
                "SELECT status FROM tp_mq_dead_letter WHERE id = ?", String.class, booked.get()));
    }

    @Test
    void sameFingerprintOnAnotherQueueIsANewDeadLetter() {
        // The uniqueness root is (fingerprint, queue): the same message dead-lettering in two
        // different DLQs is two operational facts and both must be booked.
        DeadLetterLedgerInput otherQueue = new DeadLetterLedgerInput(
                fingerprintPrefix + "-x", "mock-hit.dlq", null, null, "{}",
                DeadLetterFailureClass.UNKNOWN, null, null);
        assertTrue(ledgerService.record(otherQueue).isPresent());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_mq_dead_letter WHERE message_fingerprint = ?",
                Integer.class, fingerprintPrefix + "-x");
        assertEquals(1, count);
    }
}
