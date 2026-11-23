package com.sixbean.testpike.core.deadletter.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Translates a delivered dead-letter message into a {@link DeadLetterSignal}
 * .
 * <p>Fingerprint rule is the idempotency root made executable: broker
 * message id first (producers that set it get exact duplicate detection across
 * re-publish), otherwise a stable sha256 over queue name + raw payload; the
 * fallback is capped to fit the VARCHAR(64) column, and an oversized message id
 * also falls back rather than being truncated in the database.
 * <p>Origin fields come from the newest x-death entry (the broker appends entries
 * chronologically), while the full x-death list is kept as JSON for audit and
 * replay evidence — the adjudication requires lossless orig_exchange /
 * orig_routing_key capture for the replay primitive in .
 */
@Component
public class DeadLetterMessageDecoder {

    /** Column limit of tp_mq_dead_letter.message_fingerprint. */
    private static final int FINGERPRINT_MAX_LEN = 64;

    /** Prefix marking the digest fallback (never collides with producer message ids). */
    private static final String DIGEST_PREFIX = "sha256:";

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Compute the booking fingerprint: message id when present and column-safe,
     * otherwise sha256 over {@code queueName + payload}. Deterministic — the stock
     * migration and the live listeners must derive the same value for the same message.
     */
    public String fingerprint(String messageId, String queueName, byte[] payload) {
        if (messageId != null && !messageId.isBlank() && messageId.length() <= FINGERPRINT_MAX_LEN) {
            return messageId;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(queueName.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) '\n');
            digest.update(payload == null ? new byte[0] : payload);
            String hex = HexFormat.of().formatHex(digest.digest());
            return DIGEST_PREFIX + hex.substring(0, FINGERPRINT_MAX_LEN - DIGEST_PREFIX.length());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 must be available on every JVM we support", e);
        }
    }

    /** Decode one delivered DLQ message. Never throws on missing headers — an absent
     *  x-death simply yields null origin fields (e.g. messages moved manually into a DLQ). */
    public DeadLetterSignal decode(String deadLetterQueueName, Message message) {
        byte[] body = message.getBody() == null ? new byte[0] : message.getBody();
        String fingerprint = fingerprint(
                message.getMessageProperties().getMessageId(), deadLetterQueueName, body);
        Object xDeath = message.getMessageProperties().getHeaders().get("x-death");
        Map<?, ?> newest = newestEntry(xDeath);
        // : the capture recoverer's header text is readable with or without
        // x-death; the origin header fallback only applies when x-death is absent
        // (on the broker x-death path the header is the older value if any).
        Map<String, Object> headers = message.getMessageProperties().getHeaders();
        String failureText = blankToNull(str(headers.get(MqDeadLetterTopology.FAILURE_TEXT_HEADER)));
        return new DeadLetterSignal(
                fingerprint,
                newest != null ? blankToNull(str(newest.get("exchange")))
                        : blankToNull(str(headers.get(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER))),
                newest != null ? blankToNull(firstRoutingKey(newest.get("routing-keys")))
                        : blankToNull(str(headers.get(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER))),
                new String(body, StandardCharsets.UTF_8),
                newest == null ? "" : toJson(xDeath),
                newest == null ? null : blankToNull(str(newest.get("reason"))),
                failureText);
    }

    private Map<?, ?> newestEntry(Object xDeath) {
        if (!(xDeath instanceof List<?> list) || list.isEmpty()) {
            return null;
        }
        Object last = list.get(list.size() - 1);
        return last instanceof Map<?, ?> map ? map : null;
    }

    private String firstRoutingKey(Object routingKeys) {
        if (routingKeys instanceof List<?> list && !list.isEmpty()) {
            return str(list.get(0));
        }
        return null;
    }

    private String toJson(Object xDeath) {
        try {
            return objectMapper.writeValueAsString(xDeath);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Audit evidence must not break booking: keep the row, flag the evidence gap
            // in the JSON column so operators see why it is unreadable.
            return "{\"decode_error\":\"" + String.valueOf(e.getMessage()).replace('"', '\'') + "\"}";
        }
    }

    private static String str(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
