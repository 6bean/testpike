package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;

/**
 * Recording frame consumption plane : turns one
 * MQ batch of proxy frames into staged hold entries plus a live endpoint
 * cluster written into tp_mock_recording.summary_json.
 */
public interface MockRecordingFrameService {

    /**
     * Stage a masked frame batch for its box and refresh the cluster
     * aggregate. An unknown recording id or a sealed box rejects the batch
     * upward so the message dead-letters visibly (silent loss forbidden).
     */
    void consumeBatch(MockRecordingFrameBatchMessage batch);
}
