package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

/**
 * Local staging buffer of the recording frame stream , the
 * twin of {@link MockHitRecorder} for cassette traffic. Frames are masked
 * through the shared {@link MaskingGate} before buffering, shipped in bounded
 * batches over the injected sink, and a failed publish puts them back at the
 * front while raising {@link #degraded} — the same honesty rules the hit
 * ledger follows (see class there for the rationale).
 * <p>One extra rule only the recorder needs: a batch never straddles two
 * cassette boxes. The core consumer rejects frames aimed at a sealed box, so
 * mixing box ids into one batch would lose the whole batch's tail after a
 * re-pin; flush takes the head box id and ships only that run.
 * <p>Capacity/batch thresholds arrive from configuration (mock.recording.*),
 * never hardcoded here; the space key and rule version ride suppliers so the
 * batch always tells which pin produced it.
 */
public class MockRecordingFrameRecorder {

    private final MockRecordingFrameBatchSink sink;
    private final int batchSize;
    private final int capacity;

    private final Deque<MockRecordingFrameMessage> buffer = new ArrayDeque<>();
    private boolean degraded;
    private long overflowDrops;

    public MockRecordingFrameRecorder(MockRecordingFrameBatchSink sink,
                                      int batchSize, int capacity) {
        this.sink = sink;
        this.batchSize = Math.max(1, batchSize);
        this.capacity = Math.max(1, capacity);
    }

    /** Mask the sensitive facets, then stage the frame (servlet threads call this). */
    public synchronized void record(MockRecordingFrameMessage raw) {
        MockRecordingFrameMessage masked = mask(raw);
        while (buffer.size() >= capacity) {
            buffer.pollFirst();
            overflowDrops++;
        }
        buffer.addLast(masked);
    }

    /**
     * Ship up to batchSize frames of the HEAD box as one MQ batch. A box
     * boundary inside the drain window shortens the batch — never mix boxes.
     * On sink failure the frames go back to the front in order and the
     * degradation flag stays up until a later flush succeeds.
     */
    @Scheduled(fixedDelayString = "${testpike.mock.recording.flush-milliseconds:5000}")
    public synchronized void flush() {
        if (buffer.isEmpty()) {
            return;
        }
        long headBox = buffer.peekFirst().recordingId();
        List<MockRecordingFrameMessage> drained = new ArrayList<>(batchSize);
        while (drained.size() < batchSize && !buffer.isEmpty()
                && buffer.peekFirst().recordingId() == headBox) {
            drained.add(buffer.pollFirst());
        }
        MockRecordingFrameBatchMessage batch = new MockRecordingFrameBatchMessage(
                UUID.randomUUID().toString(), List.copyOf(drained), System.currentTimeMillis());
        try {
            sink.send(batch);
            degraded = false;
        } catch (RuntimeException ex) {
            // put the frames back at the front in order — a lost frame is worse than a late one
            Deque<MockRecordingFrameMessage> restored = new ArrayDeque<>(drained.size() + buffer.size());
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

    public synchronized boolean degraded() {
        return degraded;
    }

    public synchronized long overflowDrops() {
        return overflowDrops;
    }

    /** PII gate (shared with the ledger via MaskingGate): mask before buffering. */
    private static MockRecordingFrameMessage mask(MockRecordingFrameMessage raw) {
        return new MockRecordingFrameMessage(raw.frameId(), raw.method(), raw.path(),
                MaskingGate.headers(raw.requestHeaders()),
                raw.requestBody() == null ? null : MaskingGate.text(raw.requestBody()),
                raw.responseStatus(), MaskingGate.headers(raw.responseHeaders()),
                raw.responseBody() == null ? null : MaskingGate.text(raw.responseBody()),
                raw.latencyMs(), raw.occurredAt(), raw.spaceKey(), raw.recordingId());
    }
}
