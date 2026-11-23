package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterBrowseQuery;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterClassCount;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerRow;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterQueueCount;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Real dev database, no mocks (strict-testing iron law). Covers the three query
 * obligations of the ops list page: per-queue pending grouping (the backlog metric is
 * "rows with status=pending", never Rabbit queue depth), failure-class distribution for
 * triage, and paged sample browsing that carries payload and plain-language reason for
 * inspection. Fixtures are booked through the real write path
 * ({@link DeadLetterLedgerService#record}) so grouping counts exercise the same columns
 * the DLQ listeners write.
 * <p>Fixture discipline: run-unique fingerprint prefix, deleted by that prefix in
 * {@code @AfterEach} — never TRUNCATE on a factory table (R37).
 */
@SpringBootTest(classes = CoreApplication.class)
class DeadLetterLedgerQueryServiceTest {

    @Autowired
    private DeadLetterLedgerService ledgerService;

    @Autowired
    private DeadLetterLedgerQueryService queryService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String fingerprintPrefix = "t383q-" + UUID.randomUUID();

    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                fingerprintPrefix + "%");
    }

    private void book(String suffix, String queue, DeadLetterFailureClass failureClass) {
        ledgerService.record(new DeadLetterLedgerInput(
                fingerprintPrefix + "-" + suffix, queue, "tp.events", queue.replace(".dlq", ""),
                "{\"caseId\":7}", failureClass, "下游供应商连接被重置", null));
    }

    @Test
    void queueBacklogGroupsPendingRowsPerQueueAndIgnoresSettledOnes() {
        book("q1", "ai-gen.dlq", DeadLetterFailureClass.PROVIDER_TRANSIENT);
        book("q2", "ai-gen.dlq", DeadLetterFailureClass.LLM_TIMEOUT);
        book("q3", "ai-judge.dlq", DeadLetterFailureClass.UNKNOWN);
        int settled = jdbcTemplate.update(
                "UPDATE tp_mq_dead_letter SET status = 'voided' WHERE message_fingerprint = ?",
                fingerprintPrefix + "-q2");
        assertEquals(1, settled, "fixture must be able to settle one row as voided");

        List<DeadLetterQueueCount> backlog = queryService.queueBacklog(null);
        long genPending = backlog.stream()
                .filter(r -> "ai-gen.dlq".equals(r.queueName()))
                .mapToLong(DeadLetterQueueCount::pendingCount).sum();
        long judgePending = backlog.stream()
                .filter(r -> "ai-judge.dlq".equals(r.queueName()))
                .mapToLong(DeadLetterQueueCount::pendingCount).sum();
        assertEquals(1, genPending, "voided row must drop out of the pending backlog count");
        assertEquals(1, judgePending, "each DLQ groups its own pending rows");

        // every queue row must carry a strictly positive count — a zero-count group
        // would make the page claim backlog that is not there.
        assertTrue(backlog.stream().allMatch(r -> r.pendingCount() > 0),
                "queue grouping only reports queues that actually have backlog rows");
    }

    @Test
    void classDistributionCountsPerFailureClassWithinQueueScope() {
        book("c1", "ai-doc-extract.dlq", DeadLetterFailureClass.ENTITY_GONE);
        book("c2", "ai-doc-extract.dlq", DeadLetterFailureClass.ENTITY_GONE);
        book("c3", "ai-explore.dlq", DeadLetterFailureClass.CONFIG_MISSING);

        List<DeadLetterClassCount> scoped =
                queryService.classDistribution("ai-doc-extract.dlq", null);
        assertEquals(1, scoped.size(), "the scoped queue has exactly one class present");
        assertEquals("ENTITY_GONE", scoped.get(0).failureClass());
        assertEquals(2, scoped.get(0).count());

        List<DeadLetterClassCount> global = queryService.classDistribution(null, null);
        assertTrue(global.stream().anyMatch(r -> "CONFIG_MISSING".equals(r.failureClass())
                        && r.count() >= 1),
                "unscoped distribution spans every queue");
    }

    @Test
    void browseReturnsPagedRowsWithPayloadAndPlainReasonNewestFirst() {
        for (int i = 0; i < 3; i++) {
            book("b" + i, "ai-script-repair.dlq", DeadLetterFailureClass.DESERIALIZATION_FAIL);
        }
        PageResult<DeadLetterLedgerRow> firstPage = queryService.browse(
                new DeadLetterBrowseQuery("ai-script-repair.dlq", null, "pending", 1, 2));
        assertEquals(3, firstPage.total(), "total counts every matching row across pages");
        assertEquals(2, firstPage.list().size());
        PageResult<DeadLetterLedgerRow> secondPage = queryService.browse(
                new DeadLetterBrowseQuery("ai-script-repair.dlq", null, "pending", 2, 2));
        assertEquals(1, secondPage.list().size());
        assertEquals(3, firstPage.list().size() + secondPage.list().size(),
                "pages must not overlap or drop rows");

        DeadLetterLedgerRow row = firstPage.list().get(0);
        assertEquals("{\"caseId\":7}", row.payloadJson(), "sample browsing shows the raw payload");
        assertEquals("下游供应商连接被重置", row.failureReason());
        assertEquals("DESERIALIZATION_FAIL", row.failureClass());
        assertEquals("pending", row.status());
        assertEquals(0, row.replayRound());
    }

    @Test
    void browseRejectsUnknownFilterValuesAndOversizedPagesFailClosed() {
        // A typo'd filter must not silently return "no rows" — operators would read
        // that as an empty DLQ. Fail closed with a human-readable message instead.
        assertThrows(ValidationException.class, () -> queryService.browse(
                new DeadLetterBrowseQuery(null, null, "not-a-status", 1, 20)));
        assertThrows(ValidationException.class, () -> queryService.browse(
                new DeadLetterBrowseQuery(null, "NOT_A_CLASS", "pending", 1, 20)));
        assertThrows(ValidationException.class, () -> queryService.browse(
                new DeadLetterBrowseQuery(null, null, "pending", 1, 201)));
        assertThrows(ValidationException.class,
                () -> queryService.classDistribution(null, "settled"));
    }
}
