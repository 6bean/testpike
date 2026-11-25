package com.sixbean.testpike.admin.aichat.app.component;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window-expiry contract for the shared SSE writer (admin log audit
 * 2026-11-25: bursts of AsyncRequestTimeoutException — ghost streams whose
 * 30-minute window expired while the browser had long since navigated away,
 * plus streams that ended with no terminal word at all).
 * <p>Two obligations, both owned by SseFrameWriter so every tail door and the
 * dialog door inherit them without a second copy:
 * <ul>
 * <li>the async cycle must END inside the timeout callback — a plain abort
 * latch leaves Spring to dispatch the exception (log noise the R53 gate
 * flags) and keeps the container thread-account busy;</li>
 * <li>the client gets one degrade notice frame before the seal, in a wire
 * name outside the ledger frame vocabulary, carrying no cursor id so the
 * replay anchor of real ledger frames stays untouched.</li>
 * </ul>
 */
class SseFrameWriterWindowExpiryTest {

    @Test
    void timeoutSendsOneDegradeNoticeThenSealsTheCycleOnce() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        new SseFrameWriter(emitter);
        assertTrue(emitter.hasTimeoutCallback(),
                "the writer must own the container timeout path");

        emitter.fireTimeout();

        assertEquals(List.of("send", "seal"), emitter.trace(),
                "the notice must reach the wire BEFORE the seal, and the "
                        + "async cycle must end inside the timeout callback "
                        + "instead of ghosting into AsyncRequestTimeoutException");
        assertEquals(1, emitter.sealCount());
    }

    @Test
    void noticeFrameRidesItsOwnWireNameWithoutTouchingTheCursorSpace() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        new SseFrameWriter(emitter);

        emitter.fireTimeout();

        List<String> fragments = emitter.fragments();
        // Shape observed live (surefire report): the builder serializes
        // "event:<name>\ndata:" as one fragment, the payload as another,
        // and the block terminator "\n\n" as a third.
        assertTrue(fragments.stream().anyMatch(f -> f.startsWith("event:" + SseFrameWriter.WINDOW_EXPIRED_EVENT)),
                "the notice must be identifiable by its wire event name, got: " + fragments);
        assertTrue(fragments.contains(SseFrameWriter.WINDOW_EXPIRED_NOTICE),
                "the notice must carry its human-readable degrade sentence");
        assertFalse(fragments.stream().anyMatch(f -> f.startsWith("id:")),
                "a transport-only notice must not occupy the replay cursor space");
    }

    @Test
    void timeoutAfterAnOrderlyCompleteNeitherSendsNorReseals() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);
        writer.complete();

        emitter.fireTimeout();

        assertEquals(1, emitter.sealCount(),
                "the terminal seal already rode the normal path");
        assertEquals(0, emitter.sendCount(),
                "a completed conversation window must not gain a late notice frame");
        assertTrue(writer.isAborted());
    }

    @Test
    void writesAfterTheWindowExpiredTouchTheSocketNoMore() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        emitter.fireTimeout();
        int sendsAfterExpiry = emitter.sendCount();
        writer.write("reason", 9L, "late frame", null);
        writer.writeHeartbeat();
        writer.complete();

        assertEquals(sendsAfterExpiry, emitter.sendCount(),
                "once the window expired, the pump must not touch the socket again");
        assertEquals(1, emitter.sealCount(),
                "the expiry seal is the single seal for this cycle");
    }

    @Test
    void noticeSendFailureStillSealsTheCycle() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        emitter.failAllSends();
        new SseFrameWriter(emitter);

        emitter.fireTimeout();

        assertEquals(1, emitter.sealCount(),
                "a vanished client must not keep the async request open either");
    }
}
