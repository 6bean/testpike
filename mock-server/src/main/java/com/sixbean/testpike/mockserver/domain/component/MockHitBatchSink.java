package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mq.MockHitBatchMessage;

/**
 * The single seam between the hit recorder and the message bus ( pack C
 * . L2 tests plug a collector in; the shipped wiring is
 * {@link MockHitAmqpSink} publishing to RabbitMQ. Keeping it a one-method
 * interface means the recorder's buffering and degradation logic is testable
 * without any broker at all — the real bus path is proven at L3/L4, never
 * mocked there.
 */
public interface MockHitBatchSink {

    /** Ship one batch; throwing means "not delivered" and the recorder puts the rows back. */
    void send(MockHitBatchMessage batch);
}
