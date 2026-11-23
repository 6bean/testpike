package com.sixbean.testpike.aiservice.doc.infrastructure.mq;

import com.sixbean.testpike.aiservice.doc.domain.service.DocExtractService;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.idempotent.ConsumeOutcome;
import com.sixbean.testpike.common.idempotent.MqIdempotentConsumeSupport;
import com.sixbean.testpike.common.mq.AiDocExtractMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.ImmediateRequeueAmqpException;
import org.springframework.retry.context.RetryContextSupport;
import org.springframework.retry.support.RetrySynchronizationManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * DD batch item ㊀: the terminal-failure guarantee on the consume side.
 * <p>A failing extraction normally writes its own status back before the
 * exception re-enters the container's stateless retry; but failures that
 * ESCAPE the run's own catch faces (throws raised before the service body
 * opens, idempotency-aspect or lock outages) reach the dead-letter exchange
 * on the last attempt with nobody flipping the document out of the extracting
 * status — the job hangs forever (live evidence: the 38-minute stuck
 * "extracting" row that raised this batch).
 * <p>These fixtures pin both directions of the new hook:
 * <ul>
 *   <li>fires exactly once on the LAST retry attempt, re-throws the original
 *   exception unchanged so DLX accounting stays intact;</li>
 *   <li>stays silent on earlier attempts (no per-attempt flap while the
 *   container is still going to retry), on the crashed-holder requeue path
 *   (that is a requeue, not a terminal loss), and on a duplicate-delivery
 *   audit outage (the real execution never ran here);</li>
 *   <li>a missing retry context counts as terminal (throw goes straight to
 *   DLX) and the hook's own outage must never replace the original failure.</li>
 * </ul>
 * <p>L2 posture: the consume guard / runner / gateway / service are
 * infrastructure-boundary doubles; the write-back's own content contract is
 * pinned in DocExtractTerminalFailureWritebackServiceTest, and the live
 * three-face reconciliation runs at L3.
 */
class DocExtractTerminalFailureWritebackTest {

    private static final int MAX_ATTEMPTS = 3;
    private static final long DOC_ID = 970_000_101L;
    private static final String TOKEN = "term-wiring-gen-token";

    private final AiDocExtractIdempotentRunner runner = mock(AiDocExtractIdempotentRunner.class);
    private final MqIdempotentConsumeSupport consumeSupport = mock(MqIdempotentConsumeSupport.class);
    private final CoreGateway gateway = mock(CoreGateway.class);
    private final DocExtractService writeBack = mock(DocExtractService.class);

    @AfterEach
    void clearRetryContext() {
        RetrySynchronizationManager.clear();
    }

    private AiDocExtractConsumer consumer() {
        return new AiDocExtractConsumer(runner, consumeSupport, gateway, writeBack, MAX_ATTEMPTS);
    }

    private AiDocExtractMessage message() {
        return new AiDocExtractMessage(DOC_ID, 1L, 11L, null, TOKEN);
    }

    /** Plant the stateless-retry context of attempt {@code failedRetries + 1}. */
    private void openAttemptContext(int failedRetries) {
        RetryContextSupport context = mock(RetryContextSupport.class);
        when(context.getRetryCount()).thenReturn(failedRetries);
        RetrySynchronizationManager.register(context);
    }

    private void realExecutionThrows(RuntimeException boom) {
        when(consumeSupport.consumeOnce(any(), any(), any(), anyLong(), anyLong()))
                .thenThrow(boom);
    }

    @Test
    void lastAttemptEscapingFailureWritesBackOnceAndRethrowsOriginal() {
        openAttemptContext(MAX_ATTEMPTS - 1);
        RuntimeException boom = new IllegalStateException("provider gateway silent");
        realExecutionThrows(boom);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumer().onMessage(message()));

        assertSame(boom, thrown, "the original failure must reach the container untouched");
        ArgumentCaptor<AiDocExtractMessage> captured =
                ArgumentCaptor.forClass(AiDocExtractMessage.class);
        verify(writeBack, times(1)).handleTerminalFailure(captured.capture(), same(boom));
        // Dead-letter accounting and the status write-back must reference the
        // same generation identity carried by the message (ref identity of the
        // tp_mq_dead_letter row).
        assertEquals(DOC_ID, captured.getValue().documentId());
        assertEquals(TOKEN, captured.getValue().extractToken());
    }

    @Test
    void earlierAttemptEscapingFailureLeavesHookUntouched() {
        openAttemptContext(MAX_ATTEMPTS - 2);
        RuntimeException boom = new IllegalStateException("transient blip");
        realExecutionThrows(boom);

        assertThrows(RuntimeException.class, () -> consumer().onMessage(message()));

        verify(writeBack, never()).handleTerminalFailure(any(), any());
    }

    @Test
    void missingRetryContextCountsAsTerminal() {
        RuntimeException boom = new IllegalStateException("no retry interceptor engaged");
        realExecutionThrows(boom);

        assertThrows(RuntimeException.class, () -> consumer().onMessage(message()));

        verify(writeBack, times(1)).handleTerminalFailure(any(), same(boom));
    }

    @Test
    void crashedHolderRequeueIsNotATerminalFailure() {
        openAttemptContext(MAX_ATTEMPTS - 1);
        when(consumeSupport.consumeOnce(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(ConsumeOutcome.HEARTBEAT_DEAD_REQUEUE);

        assertThrows(ImmediateRequeueAmqpException.class,
                () -> consumer().onMessage(message()));

        verify(writeBack, never()).handleTerminalFailure(any(), any());
    }

    @Test
    void duplicateDeliveryAuditOutageDoesNotTriggerTerminalWriteBack() {
        openAttemptContext(MAX_ATTEMPTS - 1);
        when(consumeSupport.consumeOnce(any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(ConsumeOutcome.DUPLICATE);
        doThrow(new IllegalStateException("drop-audit door down")).when(gateway)
                .writeDuplicateDrop(any(), anyLong(), any(), any());

        assertThrows(IllegalStateException.class, () -> consumer().onMessage(message()));

        verify(writeBack, never()).handleTerminalFailure(any(), any());
    }

    @Test
    void hookOutagePreservesTheOriginalFailureForDeadLettering() {
        openAttemptContext(MAX_ATTEMPTS - 1);
        RuntimeException boom = new IllegalStateException("provider exploded");
        realExecutionThrows(boom);
        doThrow(new IllegalStateException("hook door down")).when(writeBack)
                .handleTerminalFailure(any(), any());

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> consumer().onMessage(message()));

        assertSame(boom, thrown, "the hook is fail-soft: the DLX reason stays the real failure");
    }

    @Test
    void legacyThreeArgAssemblySkipsTheHookWithoutCrashing() {
        openAttemptContext(MAX_ATTEMPTS - 1);
        RuntimeException boom = new IllegalStateException("escaped failure");
        realExecutionThrows(boom);
        AiDocExtractConsumer legacy =
                new AiDocExtractConsumer(runner, consumeSupport, gateway);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> legacy.onMessage(message()));

        assertSame(boom, thrown);
    }
}
