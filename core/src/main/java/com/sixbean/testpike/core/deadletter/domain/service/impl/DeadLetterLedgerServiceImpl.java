package com.sixbean.testpike.core.deadletter.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.deadletter.api.dto.DeadLetterLedgerInput;
import com.sixbean.testpike.core.deadletter.domain.service.DeadLetterLedgerService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * Ledger booking implementation over tp_mq_dead_letter .
 * <p>Idempotency is database-enforced, not application-guessed: the unique index on
 * (message_fingerprint, queue_name) plus {@code ON CONFLICT DO NOTHING} makes concurrent
 * or repeated bookings resolve to at most one row — the same proven pattern as
 * tp_schedule_fire (V98). A conflict against a pending or voided row means the
 * message is already booked, so nothing new is reported. A conflict against a
 * replayed row is a different fact: the auto-replay repost died again, so the
 * same row is flipped back to pending (adjudication O3-a-1 round budget) —
 * swallowing it would freeze the row in "replayed" forever and the loop could
 * never reach auto-void.
 * <p>Validation fails closed on the identity columns: an unusable fingerprint or queue
 * would be unreplayable and would poison the replay whitelist downstream, and a row
 * without a payload is an audit hole — such messages must surface as listener errors,
 * not as half-empty ledger rows.
 */
@Service
public class DeadLetterLedgerServiceImpl implements DeadLetterLedgerService {

    private static final String INSERT_SQL = """
            INSERT INTO tp_mq_dead_letter
                (message_fingerprint, queue_name, orig_exchange, orig_routing_key,
                 payload_json, failure_class, failure_reason, x_death_json)
            VALUES
                (:fingerprint, :queueName, :exchange, :routingKey,
                 :payloadJson, :failureClass, :failureReason, :xDeathJson)
            ON CONFLICT (message_fingerprint, queue_name) DO NOTHING
            """;

    private static final String FIND_ID_SQL = """
            SELECT id FROM tp_mq_dead_letter
            WHERE message_fingerprint = :fingerprint AND queue_name = :queueName
            """;

    /**
     * Re-death of a replayed row: flip the same row back to pending with an optimistic
     * status guard and return its id (UPDATE ... RETURNING), so concurrent duplicate
     * bookings or an already voided row produce no rows here.
     */
    private static final String REDYING_FLIP_SQL = """
            UPDATE tp_mq_dead_letter
               SET status = 'pending',
                   failure_reason = :failureReason,
                   x_death_json = :xDeathJson,
                   updated_at = now()
             WHERE message_fingerprint = :fingerprint
               AND queue_name = :queueName
               AND status = 'replayed'
            RETURNING id
            """;

    private final NamedParameterJdbcTemplate namedJdbc;

    public DeadLetterLedgerServiceImpl(NamedParameterJdbcTemplate namedJdbc) {
        this.namedJdbc = namedJdbc;
    }

    @Override
    public Optional<Long> record(DeadLetterLedgerInput input) {
        validate(input);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("fingerprint", input.messageFingerprint().trim())
                .addValue("queueName", input.queueName().trim())
                .addValue("exchange", trimToNull(input.origExchange()))
                .addValue("routingKey", trimToNull(input.origRoutingKey()))
                .addValue("payloadJson", input.payloadJson())
                .addValue("failureClass", input.failureClass().name())
                .addValue("failureReason", trimToNull(input.failureReason()))
                .addValue("xDeathJson", trimToNull(input.xDeathJson()));
        int inserted = namedJdbc.update(INSERT_SQL, params);
        if (inserted > 0) {
            return Optional.ofNullable(namedJdbc.queryForObject(FIND_ID_SQL, params, Long.class));
        }
        // Unique-conflict path. A replayed row means the auto-replay repost died again:
        // flip it back to pending (round accumulation and operator stay) so the round
        // budget can progress towards auto-void. pending/voided rows stay deduplicated.
        List<Long> flipped = namedJdbc.queryForList(REDYING_FLIP_SQL, params, Long.class);
        if (!flipped.isEmpty()) {
            return Optional.of(flipped.get(0));
        }
        return Optional.empty();
    }

    private void validate(DeadLetterLedgerInput input) {
        if (input.messageFingerprint() == null || input.messageFingerprint().isBlank()) {
            throw new ServiceException(
                    "死信落账缺少消息指纹（message_fingerprint），无法去重与补投定位，已拒绝落账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.queueName() == null || input.queueName().isBlank()) {
            throw new ServiceException(
                    "死信落账缺少来源死信队列名（queue_name），无法判定消息归属队列，已拒绝落账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.payloadJson() == null) {
            throw new ServiceException(
                    "死信落账缺少消息体原文（payload_json），补投需要原始载荷，已拒绝落账",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.failureClass() == null) {
            throw new ServiceException(
                    "死信落账缺少失败分型（failure_class），请先完成失败判定后再落账",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
