package com.sixbean.testpike.aiservice.doc.infrastructure.lifecycle;

import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightEntry;
import com.sixbean.testpike.aiservice.doc.domain.component.DocExtractInFlightRegistry;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * a graceful shutdown must hand every in-flight extraction generation
 * back as an honest failure row instead of leaving it stuck in "extracting" forever
 * (the incident face: the 8491 restart left the status burning for 38 minutes while the
 * dead owner's lease hid the redelivery). The write-back reuses the existing core
 * markFailed endpoint with the generation-token guard — zero new channel; a stale
 * generation is ignored server-side exactly like every other write-back path.
 * <p>L2 isolation only on the CoreGateway HTTP seam; registry and lifecycle semantics
 * run for real.
 */
class DocExtractShutdownWriteBackTest {

    private final DocExtractInFlightRegistry registry = new DocExtractInFlightRegistry();
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final DocExtractShutdownWriteBack writeBack =
            new DocExtractShutdownWriteBack(registry, coreGateway);

    @Test
    void stopWritesBackEveryInFlightGenerationExactlyOnceAndDrainsTheRegistry() {
        registry.register(new DocExtractInFlightEntry(91L, "tok-a"));
        registry.register(new DocExtractInFlightEntry(92L, "tok-b"));
        writeBack.start();
        writeBack.stop();

        verify(coreGateway, times(1)).markExtractionFailed(eq(91L), anyString(), eq("tok-a"));
        verify(coreGateway, times(1)).markExtractionFailed(eq(92L), anyString(), eq("tok-b"));
        assertTrue(registry.snapshot().isEmpty(), "registry must be drained so no double write");

        // A second stop (repeated lifecycle signal) never re-burns the write-back.
        writeBack.stop();
        verify(coreGateway, times(1)).markExtractionFailed(eq(91L), anyString(), eq("tok-a"));
    }

    @Test
    void stopWithNoInFlightGenerationsMakesZeroRemoteCalls() {
        writeBack.start();
        writeBack.stop();
        verifyNoInteractions(coreGateway);
    }

    @Test
    void writeBackErrorCarriesAHumanReadableInterruptSentenceNotAnInternalCode() {
        registry.register(new DocExtractInFlightEntry(93L, "tok-c"));
        writeBack.start();
        writeBack.stop();

        ArgumentCaptor<String> msg = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).markExtractionFailed(eq(93L), msg.capture(), eq("tok-c"));
        assertTrue(msg.getValue() != null && !msg.getValue().isBlank(),
                "extract_error face must stay human-readable");
        assertFalse(msg.getValue().contains("tick"), "no internal bookkeeping codes on user faces");
    }

    @Test
    void oneUnreachableDocumentNeverBreaksTheRemainingGenerations() {
        doThrow(new RuntimeException("core unreachable"))
                .when(coreGateway).markExtractionFailed(eq(91L), any(), any());
        registry.register(new DocExtractInFlightEntry(91L, "tok-a"));
        registry.register(new DocExtractInFlightEntry(92L, "tok-b"));
        writeBack.start();

        assertDoesNotThrow(() -> writeBack.stop());
        verify(coreGateway, times(1)).markExtractionFailed(eq(92L), anyString(), eq("tok-b"));
    }

    @Test
    void lifecycleStopsBeforeDefaultPhaseInfrastructuresAreReleased() {
        // Redis/connection factories live at lower phases; SmartLifecycle stops the
        // highest phase first, so the HTTP write-back seam is still usable at stop().
        assertEquals(Integer.MAX_VALUE, writeBack.getPhase());
    }
}
