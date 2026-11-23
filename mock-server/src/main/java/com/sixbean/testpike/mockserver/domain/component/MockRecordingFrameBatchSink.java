package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;

/**
 * Transport seam of the recording-frame batches : the
 * recorder holds buffered frames and hands assembled batches to this sink.
 * The production implementation publishes over RabbitMQ
 * ({@link MockRecordingFrameAmqpSink}); tests inject an in-memory capture.
 * A throwing sink is the failure signal the recorder turns into honest
 * degradation, never silent loss.
 */
public interface MockRecordingFrameBatchSink {

    /** Ship one batch; transport trouble must surface as an exception. */
    void send(MockRecordingFrameBatchMessage batch);
}
