package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hit-ledger recorder of the traffic plane . The plane
 * never writes PostgreSQL itself: rows are buffered locally and shipped as
 * batches to core over MQ. Four behaviours matter and are tested here:
 * <ul>
 * <li>batching — a flush ships at most batch-size rows, leftovers stay
 * buffered (a burst must not produce one giant message);</li>
 * <li>degradation honesty — when the sink fails the rows go back to the
 * buffer (never silently dropped) and the degraded flag flips so the
 * inspector can say "hit log degraded: in-memory only"; an overflowing
 * buffer drops oldest with a counted, visible loss;</li>
 * <li>PII masking before the row is buffered — sensitive headers become
 * ***, free-text query/body values pass the shared desensitizer;</li>
 * <li>empty flush is a no-op (no empty batch spam on the bus).</li>
 * </ul>
 * The sink is a plain one-method interface, so the MQ adapter (RabbitTemplate)
 * stays out of L2 — that wiring is exercised on the real bus at L3/L4.
 */
class MockHitRecorderTest {

    /** Collector standing in for the MQ publish call. */
    private static final class Sent {
        final List<MockHitBatchMessage> batches = new ArrayList<>();
        boolean fail;

        void send(MockHitBatchMessage batch) {
            if (fail) {
                throw new IllegalStateException("broker down");
            }
            batches.add(batch);
        }
    }

    private static MockHitRecorder recorder(Sent sent, int batchSize, int capacity) {
        return new MockHitRecorder(sent::send, () -> "demo", () -> "v9", batchSize, capacity);
    }

    private static MockHitRowMessage row(String path, boolean matched) {
        return new MockHitRowMessage("GET", path, Map.of(), Map.of(), null,
                null, null, null,
                matched, matched ? 7L : null, matched ? "order detail" : null,
                "[]", 12, null, 1L);
    }

    @Test
    void provenanceSourceSurvivesMaskingAndBatching() {
        // M10 the honest-source facet is plain metadata (never PII),
        // so the masking gate must pass it through untouched to the shipped row.
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 5, 100);
        recorder.record(new MockHitRowMessage("GET", "/ghost", Map.of(), Map.of(), null,
                200, null, "gen", false, null, null, "[]", 900, null, 7L, null, "llm"));
        recorder.flush();
        assertEquals(1, sent.batches.size());
        MockHitRowMessage shipped = sent.batches.get(0).rows().get(0);
        assertEquals("llm", shipped.source(), "provenance must arrive intact — reports count on it");
    }

    @Test
    void flushDrainsUpToBatchSizeAndKeepsTheRemainderBuffered() {
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 2, 1000);
        recorder.record(row("/orders/1", true));
        recorder.record(row("/orders/2", true));
        recorder.record(row("/orders/3", false));
        assertEquals(3, recorder.buffered());
        recorder.flush();
        assertEquals(1, sent.batches.size());
        assertEquals("demo", sent.batches.get(0).spaceKey());
        assertEquals("v9", sent.batches.get(0).ruleVersion(),
                "every ledger batch names the rule pin it was served under");
        assertEquals(2, sent.batches.get(0).rows().size());
        assertEquals(1, recorder.buffered(), "leftovers wait for the next flush, never dropped");
    }

    @Test
    void sinkFailureReturnsRowsToBufferAndMarksDegradationHonestly() {
        Sent sent = new Sent();
        sent.fail = true;
        MockHitRecorder recorder = recorder(sent, 10, 1000);
        recorder.record(row("/orders/1", true));
        recorder.flush();
        assertEquals(1, recorder.buffered(), "a failed publish must not eat the row");
        assertTrue(recorder.degraded(),
                "the inspector reads this flag to state the log is in-memory only");
        sent.fail = false;
        recorder.flush();
        assertEquals(0, recorder.buffered());
        assertFalse(recorder.degraded(), "recovery clears the degradation mark");
        assertEquals(1, sent.batches.get(0).rows().size());
    }

    @Test
    void bufferOverflowDropsOldestWithACountedLossInsteadOfGrowingForever() {
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 1, 1);
        recorder.record(row("/a", true));
        recorder.record(row("/b", true));
        assertEquals(1, recorder.overflowDrops(), "capacity loss is counted, never silent");
        assertEquals(1, recorder.buffered());
        assertEquals("/b", recorder.bufferedRows().get(0).path(), "the newest row survived");
    }

    @Test
    void sensitiveRequestFacetsAreMaskedBeforeTheRowIsBuffered() {
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 10, 1000);
        recorder.record(new MockHitRowMessage("POST", "/orders",
                Map.of("buyer", "zhangsan@example.com"),
                Map.of("Authorization", "Bearer super-secret-token",
                        "Content-Type", "application/json",
                        "Cookie", "sessionid=leak"),
                "{\"buyerPhone\":\"13812345678\"}",
                null, null, null,
                true, 9L, "create order", "[]", 5, "ci-run-7", 1L));
        MockHitRowMessage stored = recorder.bufferedRows().get(0);
        assertEquals("***", stored.headers().get("authorization"));
        assertEquals("***", stored.headers().get("cookie"));
        assertEquals("application/json", stored.headers().get("content-type"),
                "non-sensitive headers stay readable for the inspector");
        assertEquals("z***@example.com", stored.query().get("buyer"),
                "query values go through the shared desensitizer");
        assertTrue(stored.body().contains("138****5678"), "body text goes through the same pipeline");
        assertEquals("ci-run-7", stored.sessionId(), "session lane is not PII — kept as-is");
    }

    @Test
    void responseFacetsAreMaskedByTheSameGateBeforeBuffering() {
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 10, 1000);
        recorder.record(new MockHitRowMessage("GET", "/orders/1", Map.of(), Map.of(), null,
                200, Map.of("Set-Cookie", "sessionid=leak", "X-Request-Secret", "abc123",
                        "Content-Type", "application/json"),
                "{\"servicePhone\":\"13812345678\"}",
                true, 9L, "order detail", "[]", 5, null, 1L));
        MockHitRowMessage stored = recorder.bufferedRows().get(0);
        assertEquals(200, stored.responseStatus().intValue(), "status is routing metadata, kept as-is");
        assertEquals("***", stored.responseHeaders().get("set-cookie"),
                "response headers pass the same sensitive-marker gate as request headers");
        assertEquals("***", stored.responseHeaders().get("x-request-secret"));
        assertEquals("application/json", stored.responseHeaders().get("content-type"));
        assertTrue(stored.responseBody().contains("138****5678"),
                "the response body runs through the shared desensitizer too — PII red line is two-sided");
    }

    @Test
    void emptyBufferFlushIsANoOpWithoutPublishing() {
        Sent sent = new Sent();
        MockHitRecorder recorder = recorder(sent, 10, 1000);
        recorder.flush();
        assertTrue(sent.batches.isEmpty(), "no empty batch spam on the bus");
    }
}
