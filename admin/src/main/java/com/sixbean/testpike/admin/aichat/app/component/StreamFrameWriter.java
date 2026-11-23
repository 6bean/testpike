package com.sixbean.testpike.admin.aichat.app.component;

/**
 * Sink seam of the dialog SSE pump : the bridge
 * pushes ledger frames here without knowing anything about SseEmitter, which is
 * what makes the pump spectra testable at L2 against a recording fake.
 * <p>Implementations must swallow transport failures by marking themselves
 * closed — a disconnected browser is reported through {@link #isAborted},
 * never by throwing into the pump loop.
 */
public interface StreamFrameWriter {

    /** Push one protocol frame: SSE {@code event:} = frameType, {@code id:} = ledger seq. */
    void write(String frameType, long seq, String payload, String metaJson);

    /** Push a keep-alive comment frame ({@code :hb}) — no seq, never ledger-persisted. */
    void writeHeartbeat();

    /** Seal the stream (no further writes are expected after this call). */
    void complete();

    /** True once the client is gone or the stream was completed — the pump stops on it. */
    boolean isAborted();
}
