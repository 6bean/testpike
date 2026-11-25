package com.sixbean.testpike.admin.aichat.app.component;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Single source for the live tail window ceiling shared by every SSE door
 * (dialog stream, extraction tail, steps tail). The shipped default keeps the
 * legacy 30-minute hard ceiling untouched; install-time configuration
 * (sse.window-millis, driven by TESTPIKE_SSE_WINDOW_MILLIS) lets an operator
 * shorten the window — used by verification runs that must watch the expiry
 * frame land without waiting half an hour. The pumps and their own idle or
 * budget timers still seal streams far earlier; this value is only the
 * container-level backstop.
 */
@Component
public class SseWindowSettings {

    private final long windowMillis;

    public SseWindowSettings(
            @Value("${sse.window-millis:1800000}") long windowMillis) {
        if (windowMillis <= 0) {
            throw new IllegalArgumentException(
                    "sse.window-millis must be a positive number of milliseconds, got " + windowMillis
                            + " (install key TESTPIKE_SSE_WINDOW_MILLIS)");
        }
        this.windowMillis = windowMillis;
    }

    /** The configured ceiling in milliseconds. */
    public long windowMillis() {
        return windowMillis;
    }

    /** Open an emitter sealed by the shared window — the only door shape allowed. */
    public SseEmitter openEmitter() {
        return new SseEmitter(windowMillis);
    }
}
