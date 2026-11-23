package com.sixbean.testpike.common.mq;

import java.util.List;

/**
 * One buffered recording-frame batch (routing key {@code mock.recording.frame};
 * AG batch . The batch id is the consumer dedup token exactly
 * like {@link MockHitBatchMessage}: a redelivery of the same batch must be
 * staged once, so the box's summary never double-counts frames after a queue
 * requeue or a DLQ replay.
 *
 * @param batchId unique id of this batch (dedup key material)
 * @param frames already-masked frames captured while the box was open
 * @param flushedAt epoch millis of batch assembly on the plane (display only)
 */
public record MockRecordingFrameBatchMessage(String batchId,
                                             List<MockRecordingFrameMessage> frames,
                                             long flushedAt) {
}
