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

    private final SseEmitter emitter;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public SseFrameWriter(SseEmitter emitter) {
        this.emitter = emitter;
        emitter.onCompletion(() -> closed.set(true));
        emitter.onTimeout(() -> closed.set(true));
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
}
