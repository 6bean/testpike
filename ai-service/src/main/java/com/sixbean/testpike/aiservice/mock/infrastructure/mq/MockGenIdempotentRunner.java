package com.sixbean.testpike.aiservice.mock.infrastructure.mq;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.mock.domain.service.MockDraftGenService;
import com.sixbean.testpike.common.idempotent.Idempotent;
import com.sixbean.testpike.common.mq.MockGenMessage;
import org.springframework.stereotype.Component;

/**
 * Idempotent execution seam of the drafting consumer ;
 * SpecExtractIdempotentRunner precedent).
 * <p>Dedup key = jobId + publishToken: redeliveries of one generation
 * (container requeue / DLQ replay) are folded by the {@code @Idempotent}
 * guard, while a user re-trigger publishes a fresh token = a fresh run. The
 * core transition spectrum (only queued may enter running) is the second
 * layer — a token that slipped past a Redis flush still loses the race
 * instead of double-burning the LLM.
 */
@Component
public class MockGenIdempotentRunner {

    private final MockDraftGenService mockDraftGenService;

    public MockGenIdempotentRunner(MockDraftGenService mockDraftGenService) {
        this.mockDraftGenService = mockDraftGenService;
    }

    /** Run the drafting job and echo the delivery token (a replay returns the first-seen token → DUPLICATE upstream). */
    @Idempotent(key = "'ai-mock-gen:' + #message.jobId() + ':' + #message.publishToken()")
    public String run(MockGenMessage message, String token) {
        mockDraftGenService.generate(message);
        return token;
    }
}
