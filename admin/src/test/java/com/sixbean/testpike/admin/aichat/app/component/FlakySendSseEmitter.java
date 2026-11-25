package com.sixbean.testpike.admin.aichat.app.component;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test transport for the client-gone seal contract: sends fail on demand and
 * every complete() call is counted, so a leaked or duplicated async-request
 * seal turns the test red instead of hiding until the next log audit.
 */
final class FlakySendSseEmitter extends SseEmitter {

    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger seals = new AtomicInteger();
    private volatile boolean failSends;

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        sends.incrementAndGet();
        if (failSends) {
            throw new IOException("client gone");
        }
    }

    @Override
    public void complete() {
        seals.incrementAndGet();
    }

    /** Make every subsequent send throw, simulating a dead browser socket. */
    void failAllSends() {
        failSends = true;
    }

    int sendCount() {
        return sends.get();
    }

    int sealCount() {
        return seals.get();
    }
}
