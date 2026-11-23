package com.sixbean.testpike.core.deadletter.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterReplayReceipt;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Real dev database and real broker, no mocks (strict-testing iron law): fixture
 * ledger rows are inserted directly into tp_mq_dead_letter with a run-unique
 * fingerprint prefix, reposts land in a run-unique fixture queue that the test
 * consumes back with a real {@code RabbitTemplate.receive}. The full closed loop
 * (poison → retry exhaustion → DLQ → booking → replay → business consumption) is
 * the L3 acceptance script's job in .
 * <p>Coverage mirrors the adjudication obligations: lossless exchange/routing-key
 * restore with the queue-name fallback; the ENTITY_GONE hard ban on both the single
 * path and the class path; the >24h re-execution confirmation gate; the DB-side
 * second-layer dedup (replay flips pending→replayed atomically, a second attempt on
 * the same row is rejected); replay_round + operator recorded; void keeps
 * void_reason + operator on the row and never deletes it.
 * <p>Fixture discipline (R37): rows deleted by fingerprint, broker topology objects
 * are run-unique and removed afterwards — no TRUNCATE, no cross-run names.
 */
@SpringBootTest(classes = CoreApplication.class)
class DeadLetterReplayServiceTest {

    @Autowired
    private DeadLetterReplayService replayService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    private final String run = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    private final String fingerprintPrefix = "dlqreplay-" + run;
    /** The DLQ name recorded on ledger rows (queue_name column). */
    private final String ledgerQueueName = "tp.replay.fixture." + run + ".dlq";
    /** Real broker queue the reposts land in (stands in for the original business queue). */
    private final String targetQueueName = "tp.replay.fixture." + run;
    private final String targetExchangeName = "tp.replay.fixture." + run + ".x";
    private final String targetBindingKey = "fx." + run;

    @BeforeEach
    void declareTopology() {
        amqpAdmin.declareQueue(new Queue(targetQueueName, true));
        amqpAdmin.declareExchange(new TopicExchange(targetExchangeName, true, false));
        amqpAdmin.declareBinding(new Binding(targetQueueName, Binding.DestinationType.QUEUE,
                targetExchangeName, targetBindingKey, Map.of()));
    }

    @AfterEach
    void cleanFixture() {
        amqpAdmin.deleteQueue(targetQueueName);
        amqpAdmin.deleteExchange(targetExchangeName);
        jdbcTemplate.update("DELETE FROM tp_mq_dead_letter WHERE message_fingerprint LIKE ?",
                fingerprintPrefix + "%");
    }

    private long book(DeadLetterFailureClass failureClass, String exchange, String routingKey,
                      Duration age) {
        String fingerprint = fingerprintPrefix + "-" + UUID.randomUUID();
        Long id = jdbcTemplate.queryForObject(
                "INSERT INTO tp_mq_dead_letter (message_fingerprint, queue_name, orig_exchange,"
                        + " orig_routing_key, payload_json, failure_class, status, created_at)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 'pending', now() - make_interval(secs => ?))"
                        + " RETURNING id",
                Long.class, fingerprint, ledgerQueueName, exchange, routingKey,
                "{\"caseId\":" + Math.abs(fingerprint.hashCode()) + "}",
                failureClass.name(), age.toSeconds());
        return id;
    }

    private Map<String, Object> row(long id) {
        return jdbcTemplate.queryForMap(
                "SELECT status, replay_round, operator, void_reason, payload_json,"
                        + " failure_class FROM tp_mq_dead_letter WHERE id = ?", id);
    }

    @Test
    void replayRestoresOriginalExchangeAndRoutingKeyAndMarksRound() {
        long id = book(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));

        DeadLetterReplayReceipt receipt = replayService.replayOne(id, false, "ops-replay-1");

        assertEquals(id, receipt.ledgerId());
        assertEquals(targetExchangeName, receipt.repostedExchange());
        assertEquals(targetBindingKey, receipt.repostedRoutingKey());
        assertEquals(1, receipt.replayRound());

        Message reposted = rabbitTemplate.receive(targetQueueName, 5000);
        assertNotNull(reposted, "replay must land in the original queue on the original binding key");
        String bookedPayload = (String) row(id).get("payload_json");
        assertEquals(bookedPayload, new String(reposted.getBody(), StandardCharsets.UTF_8),
                "payload must be reposted byte-identical (isomorphic republish)");

        Map<String, Object> after = row(id);
        assertEquals("replayed", after.get("status"));
        assertEquals(1, ((Number) after.get("replay_round")).intValue());
        assertEquals("ops-replay-1", after.get("operator"));
    }

    @Test
    void replayFallsBackToQueueNameWhenOriginHeadersWereLost() {
        // A message moved into a DLQ manually (or a pre-V395 row) carries no orig columns:
        // the fallback routing key is the original business queue name = DLQ minus ".dlq",
        long id = book(DeadLetterFailureClass.UNKNOWN, null, null, Duration.ofMinutes(5));

        DeadLetterReplayReceipt receipt = replayService.replayOne(id, false, "ops-fallback");

        assertEquals("", receipt.repostedExchange());
        assertEquals(targetQueueName, receipt.repostedRoutingKey());
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000),
                "fallback republish must route by the queue-name routing key");
    }

    @Test
    void entityGoneIsHardRejectedOnSingleAndClassPaths() {
        long id = book(DeadLetterFailureClass.ENTITY_GONE,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));

        ServiceException single = assertThrows(ServiceException.class,
                () -> replayService.replayOne(id, true, "ops-blocked"));
        assertTrue(single.getMessage().contains("实体已删除"),
                "rejection must name the reason operators act on: " + single.getMessage());
        assertEquals("pending", row(id).get("status"), "rejected replay leaves the row untouched");
        assertNull(rabbitTemplate.receive(targetQueueName, 500),
                "a rejected replay must not touch the broker");

        book(DeadLetterFailureClass.ENTITY_GONE, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));
        assertThrows(ServiceException.class, () -> replayService.replayByFailureClass(
                ledgerQueueName, DeadLetterFailureClass.ENTITY_GONE, false, "ops-blocked"));
    }

    @Test
    void staleReplayRequiresExplicitReexecutionConfirmation() {
        long id = book(DeadLetterFailureClass.LLM_TIMEOUT,
                targetExchangeName, targetBindingKey, Duration.ofHours(25));

        ServiceException unconfirmed = assertThrows(ServiceException.class,
                () -> replayService.replayOne(id, false, "ops-stale"));
        assertTrue(unconfirmed.getMessage().contains("重新执行"),
                "the gate message must carry the re-execution semantics: " + unconfirmed.getMessage());
        assertEquals("pending", row(id).get("status"));

        DeadLetterReplayReceipt confirmed = replayService.replayOne(id, true, "ops-stale-ok");
        assertEquals(1, confirmed.replayRound());
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
    }

    @Test
    void secondReplayOfTheSameRowIsDeduplicatedByTheLedgerItself() {
        long id = book(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));
        replayService.replayOne(id, false, "ops-first");
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));

        ServiceException second = assertThrows(ServiceException.class,
                () -> replayService.replayOne(id, false, "ops-double"));
        assertTrue(second.getMessage().contains("已补投"),
                "DB-side second-layer dedup must say the row was already replayed: "
                        + second.getMessage());
        assertEquals(1, ((Number) row(id).get("replay_round")).intValue(),
                "the rejected attempt must not burn a round");
    }

    @Test
    void batchByClassReplaysOnlyThatClassAndSkipsTheBannedOne() {
        book(DeadLetterFailureClass.PROVIDER_TRANSIENT, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));
        book(DeadLetterFailureClass.PROVIDER_TRANSIENT, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));
        long goneRow = book(DeadLetterFailureClass.ENTITY_GONE, targetExchangeName,
                targetBindingKey, Duration.ofMinutes(5));
        book(DeadLetterFailureClass.LLM_TIMEOUT, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));

        int replayed = replayService.replayByFailureClass(ledgerQueueName,
                DeadLetterFailureClass.PROVIDER_TRANSIENT, false, "ops-batch");

        assertEquals(2, replayed);
        assertEquals("pending", row(goneRow).get("status"),
                "batch must never touch ENTITY_GONE rows (O3-a-2)");
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
    }

    @Test
    void queueWideReplayCoversEveryReplayablePendingRow() {
        book(DeadLetterFailureClass.CONFIG_MISSING, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));
        book(DeadLetterFailureClass.UNKNOWN, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));
        book(DeadLetterFailureClass.ENTITY_GONE, targetExchangeName, targetBindingKey,
                Duration.ofMinutes(5));

        int replayed = replayService.replayQueue(ledgerQueueName, false, "ops-queue");

        assertEquals(2, replayed, "whole-queue action = every pending row except the banned class");
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
    }

    @Test
    void voidRecordsReasonAndOperatorAndNeverDeletesTheRow() {
        long id = book(DeadLetterFailureClass.DESERIALIZATION_FAIL,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));

        assertTrue(replayService.voidLedger(id, "载荷损坏且供应商确认无法修复", "ops-void-1"));

        Map<String, Object> after = row(id);
        assertEquals("voided", after.get("status"));
        assertEquals("载荷损坏且供应商确认无法修复", after.get("void_reason"));
        assertEquals("ops-void-1", after.get("operator"));
        assertNotNull(after.get("payload_json"), "void keeps the payload — nothing is deleted");
    }

    @Test
    void voidRejectsBlankReasonUnknownRowAndAlreadyReplayedRows() {
        long id = book(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));

        assertThrows(com.sixbean.testpike.common.exception.ValidationException.class,
                () -> replayService.voidLedger(id, "  ", "ops-void"));
        assertThrows(ServiceException.class,
                () -> replayService.voidLedger(999_999_999L, "不存在", "ops-void"));

        replayService.replayOne(id, false, "ops-replay");
        assertNotNull(rabbitTemplate.receive(targetQueueName, 5000));
        assertThrows(ServiceException.class,
                () -> replayService.voidLedger(id, "重放后不能再作废", "ops-void"));
    }

    @Test
    void operatorIdentityIsRequiredOnEveryActionPath() {
        long id = book(DeadLetterFailureClass.PROVIDER_TRANSIENT,
                targetExchangeName, targetBindingKey, Duration.ofMinutes(5));
        assertThrows(com.sixbean.testpike.common.exception.ValidationException.class,
                () -> replayService.replayOne(id, false, " "));
        assertThrows(com.sixbean.testpike.common.exception.ValidationException.class,
                () -> replayService.voidLedger(id, "原因", null));
        List<Map<String, Object>> untouched = jdbcTemplate.queryForList(
                "SELECT status FROM tp_mq_dead_letter WHERE id = ?", id);
        assertEquals("pending", untouched.get(0).get("status"));
    }

    @Test
    void batchReplayDoorsRequireAQueueScopeBeforeAnySelect() {
        // A blank queue name must fail closed with a human message instead of silently
        // selecting zero rows and answering "0 reposted" (the page cannot tell the two apart).
        assertThrows(com.sixbean.testpike.common.exception.ValidationException.class,
                () -> replayService.replayQueue("  ", false, "ops-user"));
        assertThrows(com.sixbean.testpike.common.exception.ValidationException.class,
                () -> replayService.replayByFailureClass(null,
                        DeadLetterFailureClass.LLM_TIMEOUT, false, "ops-user"));
    }
}
