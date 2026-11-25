package com.sixbean.testpike.admin.aichat.app.component;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Client-gone seal contract (admin SSE log audit 2026-11-25): every
 * drawer/tail page abandoned by a browser used to latch
 * the writer {@code closed} on the next failed send while the servlet async
 * request stayed open until the 30-minute emitter timeout — the admin log
 * shows these as bursts of AsyncRequestTimeoutException (22 today, bursts of
 * 10 and 12). The transport cycle must end the moment the wire is proven
 * dead: the writer has to seal the emitter itself, so the container retires
 * the async request and no ghost timeout is ever logged.
 * A normal complete() path must still seal exactly once.
 */
class SseFrameWriterClientGoneSealTest {

    @Test
    void failedDataFrameSendSealsTheEmitterImmediately() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        emitter.failAllSends();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 1L, "text", null);

        assertTrue(writer.isAborted(), "a failed send must latch the writer aborted");
        assertEquals(1, emitter.sealCount(),
                "the proven-dead wire must seal the emitter so the async request "
                        + "retires now instead of ghosting until the 30-minute timeout");
    }

    @Test
    void failedHeartbeatSendSealsTheEmitterImmediately() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        emitter.failAllSends();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.writeHeartbeat();

        assertTrue(writer.isAborted(), "a failed heartbeat must latch the writer aborted");
        assertEquals(1, emitter.sealCount(),
                "idle dead wires are only detected by heartbeats, so the heartbeat "
                        + "failure path must seal the transport cycle too");
    }

    @Test
    void writesAfterTheWireDiedAttemptNoFurtherSendsOrSeals() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        emitter.failAllSends();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.write("reason", 1L, "first", null);
        int sealsAfterDeath = emitter.sealCount();
        writer.write("delta", 2L, "second", null);
        writer.writeHeartbeat();
        writer.complete();

        assertEquals(1, emitter.sendCount(),
                "once aborted, the writer must not touch the dead socket again");
        assertEquals(sealsAfterDeath, emitter.sealCount(),
                "sealing the same wire twice is a leak of the seal contract");
    }

    @Test
    void orderlyCompleteSealsOnceAndLaterSendFailureDoesNotReseal() {
        FlakySendSseEmitter emitter = new FlakySendSseEmitter();
        SseFrameWriter writer = new SseFrameWriter(emitter);

        writer.complete();
        assertEquals(1, emitter.sealCount(), "the terminal seal rides the normal path");
        writer.complete();
        assertEquals(1, emitter.sealCount(), "double complete from the pump must stay a no-op");

        emitter.failAllSends();
        writer.write("reason", 3L, "late", null);
        assertEquals(1, emitter.sealCount(),
                "a write racing after the terminal seal must not reopen the cycle");
    }
}
