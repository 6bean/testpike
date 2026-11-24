package com.sixbean.testpike.core.deadletter.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterReplayReceipt;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterFailureClass;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterLedgerStatus;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterReplayService;
import com.sixbean.testpike.core.deadletter.domain.component.DeadLetterReplayRefusals;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * <p>How it works: every replay path funnels through {@link #replayOne} — guard checks
 * (banned class / status / 24h re-execution confirmation) run first, then the row is
 * claimed with one atomic pending→replayed UPDATE (the DB-side second-layer dedup:
 * only the flipper owns the single repost), then the payload is republished byte-for-byte
 * to its original exchange and routing key. If the broker send fails the claim is rolled
 * back so the row returns to pending and burns no round.
 * <p>The 24h window mirrors {@code IdempotentGuard.TTL}: inside the window the consumer
 * side still dedups, so a replay is safe; outside it the message behaves as brand-new,
 * <p>Void never deletes anything: status=voided + void_reason + operator stay on the row
 */
@Service
public class DeadLetterReplayServiceImpl implements DeadLetterReplayService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterReplayServiceImpl.class);

    /** Mirrors IdempotentGuard TTL: younger rows dedup downstream, older rows re-execute. */
    private static final int IDEMPOTENT_WINDOW_HOURS = 24;

    /** Broker convention (MqDeadLetterTopology): "x.dlq" is served by original queue "x". */
    private static final String DLQ_SUFFIX = ".dlq";

    private final NamedParameterJdbcTemplate namedJdbc;
    private final RabbitTemplate rabbitTemplate;

    public DeadLetterReplayServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                       RabbitTemplate rabbitTemplate) {
        this.namedJdbc = namedJdbc;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Override
    public DeadLetterReplayReceipt replayOne(long ledgerId, boolean confirmReexecute, String operator) {
        requireOperator(operator);
        Map<String, Object> row = loadRow(ledgerId);
        String failureClass = (String) row.get("failure_class");
        if (DeadLetterFailureClass.ENTITY_GONE.name().equals(failureClass)) {
            throw new ServiceException(DeadLetterReplayRefusals.entityGone(ledgerId,
                    (String) row.get("queue_name")));
        }
        String status = (String) row.get("status");
        if (DeadLetterLedgerStatus.VOIDED.dbValue().equals(status)) {
            throw new ServiceException(DeadLetterReplayRefusals.voidedCannotReplay(ledgerId));
        }
        if (!DeadLetterLedgerStatus.PENDING.dbValue().equals(status)) {
            throw new ServiceException(DeadLetterReplayRefusals.alreadyReplayed(status, ledgerId));
        }
        Boolean isStale = namedJdbc.queryForObject(
                "SELECT created_at < now() - make_interval(hours => :hours) FROM tp_mq_dead_letter WHERE id = :id",
                new MapSqlParameterSource("hours", IDEMPOTENT_WINDOW_HOURS).addValue("id", ledgerId),
                Boolean.class);
        if (Boolean.TRUE.equals(isStale) && !confirmReexecute) {
            throw new ServiceException(DeadLetterReplayRefusals.staleNeedsConfirm(
                    ledgerId, IDEMPOTENT_WINDOW_HOURS));
        }

        String exchange = (String) row.get("orig_exchange");
        String routingKey = resolveRoutingKey(row, ledgerId);
        String repostExchange = exchange == null ? "" : exchange;

        // DB-side second-layer dedup: flip pending→replayed atomically; the winner owns the only repost.
        int claimed = namedJdbc.update(
                "UPDATE tp_mq_dead_letter SET status = :replayed, replay_round = replay_round + 1,"
                        + " operator = :operator, updated_at = now() WHERE id = :id AND status = :pending",
                new MapSqlParameterSource("replayed", DeadLetterLedgerStatus.REPLAYED.dbValue())
                        .addValue("operator", operator)
                        .addValue("id", ledgerId)
                        .addValue("pending", DeadLetterLedgerStatus.PENDING.dbValue()));
        if (claimed == 0) {
            throw new ServiceException(DeadLetterReplayRefusals.claimLost(ledgerId));
        }

        String payload = (String) row.get("payload_json");
        try {
            Message message = buildReplayMessage(payload);
            rabbitTemplate.send(repostExchange, routingKey, message);
        } catch (RuntimeException ex) {
            // Broker unreachable or send rejected: restore the claim so the row goes back to
            // pending without burning a round (the next attempt starts clean).
            namedJdbc.update(
                    "UPDATE tp_mq_dead_letter SET status = :pending, replay_round = replay_round - 1,"
                            + " updated_at = now() WHERE id = :id AND status = :replayed",
                    new MapSqlParameterSource("pending", DeadLetterLedgerStatus.PENDING.dbValue())
                            .addValue("replayed", DeadLetterLedgerStatus.REPLAYED.dbValue())
                            .addValue("id", ledgerId));
            log.warn("Dead-letter repost failed, claim restored: ledgerId={} exchange={} rk={} cause={}",
                    ledgerId, repostExchange, routingKey, ex.getMessage());
            throw new ServiceException(DeadLetterReplayRefusals.sendFailed(ex.getMessage(), ledgerId));
        }

        int round = currentRound(ledgerId);
        return new DeadLetterReplayReceipt(ledgerId, (String) row.get("message_fingerprint"),
                (String) row.get("queue_name"), repostExchange, routingKey, round);
    }

    @Override
    public int replayByFailureClass(String deadLetterQueueName, DeadLetterFailureClass failureClass,
                                    boolean confirmReexecute, String operator) {
        requireOperator(operator);
        requireQueueName(deadLetterQueueName);
        if (failureClass == null) {
            throw new ValidationException("补投必须指定失败分型（pending 行按分型圈定范围）");
        }
        if (failureClass == DeadLetterFailureClass.ENTITY_GONE) {
            throw new ServiceException(DeadLetterReplayRefusals.batchBannedClass(deadLetterQueueName));
        }
        String sql = "SELECT id FROM tp_mq_dead_letter WHERE queue_name = :queue AND status = :pending"
                + " AND failure_class = :failureClass" + replayableAgeClause(confirmReexecute);
        List<Long> ids = namedJdbc.queryForList(sql,
                new MapSqlParameterSource("queue", deadLetterQueueName)
                        .addValue("pending", DeadLetterLedgerStatus.PENDING.dbValue())
                        .addValue("failureClass", failureClass.name()),
                Long.class);
        return replayEach(ids, confirmReexecute, operator);
    }

    @Override
    public int replayQueue(String deadLetterQueueName, boolean confirmReexecute, String operator) {
        requireOperator(operator);
        requireQueueName(deadLetterQueueName);
        String sql = "SELECT id FROM tp_mq_dead_letter WHERE queue_name = :queue AND status = :pending"
                + " AND failure_class <> :bannedClass" + replayableAgeClause(confirmReexecute);
        List<Long> ids = namedJdbc.queryForList(sql,
                new MapSqlParameterSource("queue", deadLetterQueueName)
                        .addValue("pending", DeadLetterLedgerStatus.PENDING.dbValue())
                        .addValue("bannedClass", DeadLetterFailureClass.ENTITY_GONE.name()),
                Long.class);
        return replayEach(ids, confirmReexecute, operator);
    }

    @Override
    public boolean voidLedger(long ledgerId, String reason, String operator) {
        if (reason == null || reason.isBlank()) {
            throw new ValidationException("作废必须填写原因（账面上永久保留，供后续追查）");
        }
        requireOperator(operator);
        int updated = namedJdbc.update(
                "UPDATE tp_mq_dead_letter SET status = :voided, void_reason = :reason,"
                        + " operator = :operator, updated_at = now()"
                        + " WHERE id = :id AND status = :pending",
                new MapSqlParameterSource("voided", DeadLetterLedgerStatus.VOIDED.dbValue())
                        .addValue("reason", reason)
                        .addValue("operator", operator)
                        .addValue("id", ledgerId)
                        .addValue("pending", DeadLetterLedgerStatus.PENDING.dbValue()));
        if (updated == 1) {
            return true;
        }
        // Nothing flipped: classify honestly instead of staying silent (a typo'd id must not
        // read as "already handled"). The row itself is never deleted either way.
        Map<String, Object> row = loadRow(ledgerId);
        String status = (String) row.get("status");
        if (DeadLetterLedgerStatus.REPLAYED.dbValue().equals(status)) {
            throw new ServiceException(DeadLetterReplayRefusals.replayedCannotVoid(ledgerId));
        }
        if (DeadLetterLedgerStatus.VOIDED.dbValue().equals(status)) {
            throw new ServiceException(DeadLetterReplayRefusals.alreadyVoided(ledgerId));
        }
        throw new ServiceException(DeadLetterReplayRefusals.voidUnsupportedStatus(status, ledgerId));
    }

    /**
     * Replay the pre-selected ids one by one; per-row rejections (someone else flipped the
     * row, a guard tripped mid-batch) are skipped so a batch never aborts halfway.
     */
    private int replayEach(List<Long> ids, boolean confirmReexecute, String operator) {
        int replayed = 0;
        for (Long id : ids) {
            try {
                replayOne(id, confirmReexecute, operator);
                replayed++;
            } catch (ServiceException | ValidationException ex) {
                log.info("Batch replay skipped row {}: {}", id, ex.getMessage());
            }
        }
        return replayed;
    }

    private static String replayableAgeClause(boolean confirmReexecute) {
        return confirmReexecute ? ""
                : " AND created_at >= now() - make_interval(hours => " + IDEMPOTENT_WINDOW_HOURS + ")";
    }

    private Map<String, Object> loadRow(long ledgerId) {
        try {
            return namedJdbc.queryForMap(
                    "SELECT message_fingerprint, queue_name, orig_exchange, orig_routing_key,"
                            + " payload_json, failure_class, status FROM tp_mq_dead_letter WHERE id = :id",
                    new MapSqlParameterSource("id", ledgerId));
        } catch (EmptyResultDataAccessException ex) {
            throw new ServiceException(DeadLetterReplayRefusals.ledgerNotFound(ledgerId));
        }
    }

    /**
 * Repost target: the recorded original exchange/routing key; when the origin headers
 * were lost (manual moves, pre-V395 rows) fall back to the default exchange plus the
 * declarations pin the routing key to the queue name).
 */
    private static String resolveRoutingKey(Map<String, Object> row, long ledgerId) {
        String recorded = (String) row.get("orig_routing_key");
        if (recorded != null && !recorded.isBlank()) {
            return recorded;
        }
        String queue = (String) row.get("queue_name");
        if (queue != null && queue.endsWith(DLQ_SUFFIX)) {
            return queue.substring(0, queue.length() - DLQ_SUFFIX.length());
        }
        throw new ServiceException(DeadLetterReplayRefusals.routingUnresolvable(queue, ledgerId));
    }

    private static Message buildReplayMessage(String payloadJson) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        return new Message(payloadJson.getBytes(StandardCharsets.UTF_8), props);
    }

    private int currentRound(long ledgerId) {
        Integer round = namedJdbc.queryForObject(
                "SELECT replay_round FROM tp_mq_dead_letter WHERE id = :id",
                new MapSqlParameterSource("id", ledgerId), Integer.class);
        return round == null ? 0 : round;
    }

    private static void requireOperator(String operator) {
        if (operator == null || operator.isBlank()) {
            throw new ValidationException("操作人身份缺失，补投/作废必须记录操作人；请从管理页面操作或补充调用方身份");
        }
    }

    /** Batch scope fail-closed: a blank queue name would silently select zero rows (service test pins). */
    private static void requireQueueName(String deadLetterQueueName) {
        if (deadLetterQueueName == null || deadLetterQueueName.isBlank()) {
            throw new ValidationException("批量补投必须指定死信队列（队列名圈定账本范围）；"
                    + "请先在队列分组中选择目标队列");
        }
    }
}
