package com.sixbean.testpike.admin.aichat.app.component;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test transport for the client-gone seal contract: sends fail on demand and
 * every complete() call is counted, so a leaked or duplicated async-request
 * seal turns the test red instead of hiding until the next log audit.
 */
final class FlakySendSseEmitter extends SseEmitter {

    /** Ordered wire trace: every send and every seal lands here in order. */
    private final List<String> trace = Collections.synchronizedList(new ArrayList<>());
    /** Raw payload fragments of every sent block (for wire-content asserts). */
    private final List<String> fragments = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger sends = new AtomicInteger();
    private final AtomicInteger seals = new AtomicInteger();
    private volatile boolean failSends;
    private volatile Runnable timeoutCallback;
    /** Set by containerTimeoutDispatch() before the callback runs, mirroring
     *  the framework's own completion latch ordering. */
    private volatile boolean containerCompleted;

    @Override
    public void onTimeout(Runnable callback) {
        this.timeoutCallback = callback;
        super.onTimeout(callback);
    }

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        if (containerCompleted) {
            throw new IllegalStateException(
                    "ResponseBodyEmitter has already completed");
        }
        sends.incrementAndGet();
        trace.add("send");
        collectFragments(builder);
        if (failSends) {
            throw new IOException("client gone");
        }
    }

    @Override
    public void complete() {
        seals.incrementAndGet();
        trace.add("seal");
    }

    /** Simulate the container's async-window timeout dispatch. */
    void fireTimeout() {
        Runnable callback = timeoutCallback;
        if (callback != null) {
            callback.run();
        }
    }

    /** True when the writer never registered a timeout callback at all. */
    boolean hasTimeoutCallback() {
        return timeoutCallback != null;
    }

    /**
     * Replay the real Spring 6.2.8 container-timeout ordering: the framework
     * callback marks the emitter completed BEFORE the registered delegates
     * run, so any emitter.send() from inside the timeout callback throws
     * IllegalStateException ("ResponseBodyEmitter has already completed").
     * The plain fireTimeout() above models the test-side callback call and
     * cannot see that ordering - this method is what makes the raw-response
     * notice path provable in-process (live defect 2026-11-26).
     */
    void containerTimeoutDispatch() {
        containerCompleted = true;
        Runnable callback = timeoutCallback;
        if (callback != null) {
            callback.run();
        }
    }

    List<String> trace() {
        return List.copyOf(trace);
    }

    List<String> fragments() {
        return List.copyOf(fragments);
    }

    /**
     * Snapshot the block's payload objects before the send decision, so a
     * degrade notice is content-inspectable even when the socket is dead.
     * DataWithMediaType is container-internal; reflection stays test-side.
     */
    private void collectFragments(SseEventBuilder builder) {
        for (Object entry : builder.build()) {
            try {
                Method getter = entry.getClass().getDeclaredMethod("getData");
                getter.setAccessible(true);
                Object data = getter.invoke(entry);
                if (data != null) {
                    fragments.add(data.toString());
                }
            } catch (ReflectiveOperationException unreadableEntry) {
                // Fragment shape changed upstream: content asserts degrade to
                // counts only, never to a silent pass on missing evidence.
            }
        }
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
