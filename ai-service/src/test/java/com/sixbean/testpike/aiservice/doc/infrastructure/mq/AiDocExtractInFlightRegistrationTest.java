package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightEntry;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightRegistry;
import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

/**
 * the runner seam (the single point where a message actually starts
 * real work after the idempotency aspect admits it) must keep the generation in the
 * in-flight registry for the whole extract and release it in every exit path —
 * including a thrown failure — otherwise the shutdown write-back would report rows
 * that already reached a terminal state.
 */
class AiDocExtractInFlightRegistrationTest {

    private final DocExtractInFlightRegistry registry = new DocExtractInFlightRegistry();
    private final DocExtractService service = mock(DocExtractService.class);
    private final AiDocExtractIdempotentRunner runner =
            new AiDocExtractIdempotentRunner(service, registry);

    private static AiDocExtractMessage message(long docId, String token) {
        return new AiDocExtractMessage(docId, 1L, 1L, null, token);
    }

    @Test
    void generationIsVisibleInTheRegistryForTheWholeExtractAndReleasedAfterwards() {
        AtomicReference<List<DocExtractInFlightEntry>> duringCall = new AtomicReference<>();
        doAnswer(invocation -> {
            duringCall.set(registry.snapshot());
            return null;
        }).when(service).extract(any());

        runner.run(message(91L, "tok-x"), "tok-x", 1800L);

        assertEquals(1, duringCall.get().size(), "in-flight generation must be registered");
        assertEquals(91L, duringCall.get().get(0).documentId());
        assertEquals("tok-x", duringCall.get().get(0).extractToken());
        assertTrue(registry.snapshot().isEmpty(), "terminal state releases the registration");
    }

    @Test
    void aFailingExtractStillReleasesTheRegistration() {
        try {
            doThrow(new IllegalStateException("boom")).when(service).extract(any());
            assertThrows(IllegalStateException.class,
                    () -> runner.run(message(92L, "tok-y"), "tok-y", 1800L));
            assertTrue(registry.snapshot().isEmpty());
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void singleArgAssemblyKeepsTheSeamAbsentWithoutBreakingRun() {
        // Existing overload used by pre-slice tests must stay byte-identical: no
        // registry injected = zero registration, run() still delegates.
        AiDocExtractIdempotentRunner legacy = new AiDocExtractIdempotentRunner(service);
        legacy.run(message(93L, "tok-z"), "tok-z", 1800L);
        assertTrue(registry.snapshot().isEmpty());
    }
}
