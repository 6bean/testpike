package com.sixbean.testpike.core.deadletter.domain.component;

import com.sixbean.testpike.common.mq.MqDeadLetterTopology;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decoder tests for the DLQ egress path .
 * <p>Pure JVM unit test — the decoder is a side-effect-free translation from a delivered
 * broker message to the ledger signal, so no database or Spring context is involved.
 * Coverage mirrors the three contracts the idempotency root depends on:
 * fingerprint derivation (broker message id first, queue+payload digest fallback,
 * column-length safe), x-death capture (newest entry wins for origin fields, whole
 * list kept as JSON for audit), and payload text extraction.
 */
class DeadLetterMessageDecoderTest {

    private final DeadLetterMessageDecoder decoder = new DeadLetterMessageDecoder();

    private static Message message(String messageId, String body) {
        MessageProperties p = new MessageProperties();
        p.setMessageId(messageId);
        return new Message(body.getBytes(StandardCharsets.UTF_8), p);
    }

    @Test
    void fingerprintPrefersBrokerMessageId() {
        String fp = decoder.fingerprint("msg-42", "testpike.ai-gen.dlq",
                "anything".getBytes(StandardCharsets.UTF_8));
        assertEquals("msg-42", fp);
    }

    @Test
    void fingerprintFallsBackToStableDigestWithoutMessageId() {
        byte[] payload = "{\"taskId\":1}".getBytes(StandardCharsets.UTF_8);
        String first = decoder.fingerprint(null, "testpike.ai-gen.dlq", payload);
        String second = decoder.fingerprint("  ", "testpike.ai-gen.dlq", payload);
        assertEquals(first, second, "same queue+payload must produce the same fingerprint twice");
        assertTrue(first.startsWith("sha256:"));
        assertTrue(first.length() <= 64, "fingerprint must fit the VARCHAR(64) column");
    }

    @Test
    void fingerprintDigestDiffersByQueueAndPayload() {
        byte[] payload = "same".getBytes(StandardCharsets.UTF_8);
        String gen = decoder.fingerprint(null, "testpike.ai-gen.dlq", payload);
        String judge = decoder.fingerprint(null, "testpike.ai-judge.dlq", payload);
        String other = decoder.fingerprint(null, "testpike.ai-gen.dlq", "other".getBytes(StandardCharsets.UTF_8));
        assertNotEquals(gen, judge);
        assertNotEquals(gen, other);
    }

    @Test
    void overlyLongMessageIdStillFallsBackToDigest() {
        String tooLong = "m".repeat(80);
        String fp = decoder.fingerprint(tooLong, "testpike.ai-gen.dlq", "p".getBytes(StandardCharsets.UTF_8));
        assertTrue(fp.startsWith("sha256:"), "message ids longer than the column must not be stored raw");
    }

    @Test
    void decodesOriginAndReasonFromNewestXDeathEntry() {
        Map<String, Object> older = Map.of(
                "queue", "testpike.ai-gen", "exchange", "old.exchange", "reason", "rejected",
                "routing-keys", List.of("old.rk"), "time", 1L, "count", 1L);
        Map<String, Object> newest = Map.of(
                "queue", "testpike.ai-gen", "exchange", "testpike.direct", "reason", "rejected",
                "routing-keys", List.of("testpike.ai-gen"), "time", 2L, "count", 3L);
        MessageProperties p = new MessageProperties();
        p.setMessageId("mid-1");
        p.setHeader("x-death", List.of(older, newest));
        Message message = new Message("{\"a\":1}".getBytes(StandardCharsets.UTF_8), p);

        DeadLetterSignal signal = decoder.decode("testpike.ai-gen.dlq", message);

        assertEquals("mid-1", signal.messageFingerprint());
        assertEquals("testpike.direct", signal.origExchange());
        assertEquals("testpike.ai-gen", signal.origRoutingKey());
        assertEquals("rejected", signal.xDeathReason());
        assertEquals("{\"a\":1}", signal.payloadText());
        assertTrue(signal.xDeathJson().contains("old.exchange"), "full x-death list must be kept for audit");
    }

    @Test
    void decodesWithoutXDeathGracefully() {
        DeadLetterSignal signal = decoder.decode("testpike.mock-hit.dlq", message(null, "x"));
        assertNull(signal.origExchange());
        assertNull(signal.origRoutingKey());
        assertNull(signal.xDeathReason());
        assertTrue(signal.messageFingerprint().startsWith("sha256:"));
        assertEquals("", signal.xDeathJson());
    }

    @Test
    void readsFailureTextHeaderAndBackfillsOriginWhenXDeathAbsent() {
        // capture path: the recoverer republished clone carries the
        // failure text and the origin fields on headers (no x-death entry).
        MessageProperties p = new MessageProperties();
        p.setMessageId("mid-ft");
        p.setHeader(MqDeadLetterTopology.FAILURE_TEXT_HEADER, "ConnectException: Connection refused");
        p.setHeader(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER, "testpike.direct");
        p.setHeader(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER, "testpike.ai-gen");
        Message m = new Message("{}".getBytes(StandardCharsets.UTF_8), p);

        DeadLetterSignal signal = decoder.decode("testpike.ai-gen.dlq", m);

        assertEquals("ConnectException: Connection refused", signal.failureText());
        assertEquals("testpike.direct", signal.origExchange());
        assertEquals("testpike.ai-gen", signal.origRoutingKey());
    }

    @Test
    void xDeathOriginWinsOverHeaderFallbackAndFailureTextIsAlwaysRead() {
        // Origin fields come from x-death whenever it exists; the header fallback
        // only fills the absent-x-death branch. failureText is read either way.
        MessageProperties p = new MessageProperties();
        p.setMessageId("mid-both");
        p.setHeader("x-death", List.of(Map.of(
                "exchange", "testpike.direct",
                "routing-keys", List.of("testpike.ai-chat"),
                "reason", "rejected")));
        p.setHeader(MqDeadLetterTopology.FAILURE_TEXT_HEADER, "SocketTimeoutException: read timed out");
        p.setHeader(MqDeadLetterTopology.DEAD_ORIGIN_EXCHANGE_HEADER, "stale.exchange");
        p.setHeader(MqDeadLetterTopology.DEAD_ORIGIN_ROUTING_KEY_HEADER, "stale.key");
        Message m = new Message("{}".getBytes(StandardCharsets.UTF_8), p);

        DeadLetterSignal signal = decoder.decode("testpike.ai-chat.dlq", m);

        assertEquals("testpike.direct", signal.origExchange());
        assertEquals("testpike.ai-chat", signal.origRoutingKey());
        assertEquals("SocketTimeoutException: read timed out", signal.failureText());
    }
}

