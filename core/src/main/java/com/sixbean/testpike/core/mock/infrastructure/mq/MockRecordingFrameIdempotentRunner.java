package com.sixbean.testpike.core.mock.infrastructure.mq;

import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import com.sixbean.testpike.core.mock.domain.service.MockRecordingFrameService;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution piece of the recording frame consumer (batchId is the
 * dedup token material): a redelivered batch replays the first staging instead
 * of writing the same frames into the hold and the cluster twice — same shape
 * as MockHitIdempotentRunner behind MqIdempotentConsumeSupport.
 */
@Component
public class MockRecordingFrameIdempotentRunner {

    private final MockRecordingFrameService frameService;

    public MockRecordingFrameIdempotentRunner(MockRecordingFrameService frameService) {
        this.frameService = frameService;
    }

    /** Stage the batch and echo this delivery's token (a replay returns the first token). */
    @Idempotent(key = "'mock-rec-frame:' + #message.batchId()")
    public String run(MockRecordingFrameBatchMessage message, String token) {
        frameService.consumeBatch(message);
        return token;
    }
}
