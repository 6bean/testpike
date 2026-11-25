package com.sixbean.testpike.admin.aichat.app.component;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SseEmitter-backed {@link StreamFrameWriter}: the only place where the dialog
 * pump touches the servlet transport.
 * <p>A dead browser is never an exception path — any send failure latches
 * {@code closed} so the pump loop exits on {@link #isAborted()} (the ledger
 * stays authoritative: a reconnect replays from Last-Event-ID, nothing is
 * lost by a dropped socket). The heartbeat lands as a real SSE comment frame
 * ({@code :hb}) per the frame-protocol adjudication, so proxies see traffic
 * without the client ever parsing it.
 */
public class SseFrameWriter implements StreamFrameWriter {

    /**
     * Wire-only event name of the window-expiry degrade notice. Deliberately
     * outside the ledger frame vocabulary: it never lands in the ledger, so
     * replay semantics of the nine stored frame types are untouched.
     */
    public static final String WINDOW_EXPIRED_EVENT = "stream-window-expired";

    /** Human-readable degrade sentence carried by {@link #WINDOW_EXPIRED_EVENT}. */
    public static final String WINDOW_EXPIRED_NOTICE =
            "实时进度推送窗口已到时关闭；任务仍在后台继续，页面会自动改用轮询续读进度，无需手动操作。";

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseFrameWriter(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(this::sealExpiredWindow);
        emitter.onError(ex -> closed.set(true));
    }

    @Override
    public void write(String frameType, long seq, String payload, String metaJson) {
        if (closed.get()) {
            return;
        }
        try {
            SseEmitter.SseEventBuilder event = SseEmitter.event()
                    .id(Long.toString(seq))
                    .name(frameType);
            // cite cards: the ledger's machine-readable meta crosses the
            // wire as a `:meta ` comment of the SAME block — spec-compliant
            // parsers ignore comment lines, so every older consumer keeps
            // working while the dock decoder attaches it to its frame. A meta
            // value with raw newlines (which would split the block) degrades
            // honestly: the frame rides, the side-channel is dropped.
            String meta = metaJson == null ? "" : metaJson.trim();
            if (!meta.isEmpty() && meta.indexOf('\n') < 0 && meta.indexOf('\r') < 0) {
                event.comment("meta " + meta);
            }
            // Wire padding for space-leading payloads: every spec-compliant
            // reader drops exactly one leading U+0020 after "data:", and
            // Spring writes the payload right after the colon without a
            // separator of its own. LLM reason/delta frames split
            // mid-sentence, so a payload whose first byte is its own
            // inter-word space would be eaten on the browser leg ("In" +
            // "eed to" -> "Ineed to"). Pad one extra byte so the mandated
            // drop leaves the payload byte-for-byte intact; anything not
            // starting with a space rides unchanged.
            String text = payload == null ? "" : payload;
            if (text.startsWith(" ")) {
                text = " " + text;
            }
            event.data(text, MediaType.TEXT_PLAIN);
            emitter.send(event);
        } catch (Exception clientGone) {
            sealDeadWire();
        }
    }

    @Override
    public void writeHeartbeat() {
        if (closed.get()) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().comment("hb"));
        } catch (Exception clientGone) {
            sealDeadWire();
        }
    }

    @Override
    public void complete() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception alreadyGone) {
                // The socket died first — nothing left to seal.
            }
        }
    }

    @Override
    public boolean isAborted() {
        return closed.get();
    }

    /**
     * Latches the writer aborted AND ends the servlet async cycle, so the
     * container retires the request the moment the wire is proven dead.
     * Without the seal the request stayed open until the 30-minute emitter
     * timeout and the admin log filled with AsyncRequestTimeoutException
     * bursts for browsers that had long since navigated away (log audit
     * 2026-11-25: 22 ghost timeouts in one day). The first failed
     * send owns the single seal; later paths are no-ops.
     */
    private void sealDeadWire() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.complete();
            } catch (Exception alreadyGone) {
                // The container retired the request itself — nothing left to seal.
            }
        }
    }

    /**
     * Container-side window expiry: one degrade notice reaches the wire,
     * then the async cycle is completed INSIDE the callback. A plain abort
     * latch (the previous behavior) left Spring to dispatch the timeout as
     * AsyncRequestTimeoutException — the repeated WARN bursts in the admin
     * log (R53 audit 2026-11-25) — and the client saw a silent stream end
     * with no word about what happened. Completing here retires the request
     * cleanly; the notice is transport-only (no cursor id, never a ledger
     * row), so reconnecting consumers still replay from the true anchor.
     */
    private void sealExpiredWindow() {
        if (closed.compareAndSet(false, true)) {
            try {
                emitter.send(SseEmitter.event()
                        .name(WINDOW_EXPIRED_EVENT)
                        .data(WINDOW_EXPIRED_NOTICE, MediaType.TEXT_PLAIN));
            } catch (Exception wireGone) {
                // The browser vanished before the notice; the ledger keeps
                // every real frame, so nothing is lost by skipping it.
            }
            try {
                emitter.complete();
            } catch (Exception alreadyRetired) {
                // The container closed the cycle first — same end state.
            }
        }
    }
}
