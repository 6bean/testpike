package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Local staging buffer of the traffic plane's hit ledger ,
 * AG design D-b): every served request leaves one masked row here, and rows
 * travel to core only as MQ batches — the data plane never writes PostgreSQL,
 * because mocked traffic at full rate must never put load on the database.
 * <p>Honesty rules:
 * <ul>
 * <li>masking happens at record time, before anything is buffered, so an
 * unmasked PII value can never escape this process (shared desensitizer,
 * sensitive headers star-valued);</li>
 * <li>a failed publish returns the rows to the buffer and flips
 * {@link #degraded} — the inspector says "hit log degraded: in-memory
 * only" instead of pretending the ledger is complete;</li>
 * <li>an overflowing buffer drops the OLDEST rows and counts the loss
 * ({@link #overflowDrops}) — a live incident must not be evicted by an
 * old one, and silent loss is forbidden;</li>
 * <li>batching is bounded twice over: at most batchSize rows per message,
 * and a scheduled flush so low-traffic spaces still show rows in the
 * inspector within flush-seconds.</li>
 * </ul>
 * The capacity/batch/flush values arrive from configuration (threshold keys
 * mock.hit.*), never hardcoded here.
 */
public class MockHitRecorder {

    private final MockHitBatchSink sink;
    private final Supplier<String> spaceKeySupplier;
    private final Supplier<String> ruleVersionSupplier;
    private final int batchSize;
    private final int capacity;

    private final Deque<MockHitRowMessage> buffer = new ArrayDeque<>();
    private boolean degraded;
    private long overflowDrops;

    public MockHitRecorder(MockHitBatchSink sink, Supplier<String> spaceKeySupplier,
                           Supplier<String> ruleVersionSupplier, int batchSize, int capacity) {
        this.sink = sink;
        this.spaceKeySupplier = spaceKeySupplier;
        this.ruleVersionSupplier = ruleVersionSupplier;
        this.batchSize = Math.max(1, batchSize);
        this.capacity = Math.max(1, capacity);
    }

    /** Mask the sensitive facets, then stage the row (thread-safe: servlet threads call this). */
    public synchronized void record(MockHitRowMessage raw) {
        MockHitRowMessage masked = mask(raw);
        while (buffer.size() >= capacity) {
            buffer.pollFirst();
            overflowDrops++;
        }
        buffer.addLast(masked);
    }

    /**
     * Ship up to batchSize buffered rows as one MQ batch. On sink failure the
     * rows go back to the front of the buffer untouched and the degradation
     * flag stays up until a later flush succeeds.
     */
    @Scheduled(fixedDelayString = "${testpike.mock.hit.flush-milliseconds:5000}")
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        List<MockHitRowMessage> drained = new ArrayList<>(batchSize);
        while (drained.size() < batchSize && !buffer.isEmpty()) {
            drained.add(buffer.pollFirst());
        }
        MockHitBatchMessage batch = new MockHitBatchMessage(spaceKeySupplier.get(),
                ruleVersionSupplier.get(), UUID.randomUUID().toString(),
                List.copyOf(drained), System.currentTimeMillis());
        try {
            sink.send(batch);
            degraded = false;
        } catch (RuntimeException ex) {
            // put the rows back at the front in order — a lost ledger row is worse than a late one
            Deque<MockHitRowMessage> restored = new ArrayDeque<>(drained.size() + buffer.size());
            drained.forEach(restored::addLast);
            buffer.forEach(restored::addLast);
            buffer.clear();
            buffer.addAll(restored);
            degraded = true;
        }
    }

    public synchronized int buffered() {
        return buffer.size();
    }

    /** Snapshot for the inspector/self-check page (already masked — buffer stores masked rows). */
    public synchronized List<MockHitRowMessage> bufferedRows() {
        return List.copyOf(buffer);
    }

    public synchronized boolean degraded() {
        return degraded;
    }

    public synchronized long overflowDrops() {
        return overflowDrops;
    }

    /** PII gate: everything leaving the servlet thread is masked before it is buffered. */
    private static MockHitRowMessage mask(MockHitRowMessage raw) {
        return new MockHitRowMessage(raw.method(), raw.path(),
                MaskingGate.query(raw.query()), MaskingGate.headers(raw.headers()),
                raw.body() == null ? null : MaskingGate.text(raw.body()),
                raw.responseStatus(), MaskingGate.headers(raw.responseHeaders()),
                raw.responseBody() == null ? null : MaskingGate.text(raw.responseBody()),
                raw.matched(), raw.stubId(), raw.stubName(),
                MaskingGate.text(raw.matchTrace()),
                raw.latencyMs(), raw.sessionId(), raw.occurredAt(), raw.faultApplied(),
                raw.source());
    }
}
