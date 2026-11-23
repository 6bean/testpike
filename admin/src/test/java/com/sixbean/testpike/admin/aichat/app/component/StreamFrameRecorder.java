package com.sixbean.testpike.admin.aichat.app.component;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared {@link StreamFrameWriter} fake for the SSE pump spectra (R40 single source:
 * dialog, extraction and steps-gen bridges all record through this one fake instead of
 * carrying three copies of the same recording logic).
 * <p>The recorder never touches a real SseEmitter: it captures frame type, ledger seq,
 * payload and meta per write, counts keep-alive beats, and exposes the terminal seal
 * plus a settable abort flag so tests can simulate a disconnected browser.
 */
public final class StreamFrameRecorder implements StreamFrameWriter {

    public final List<String> frameTypes = new ArrayList<>();
    public final List<Long> seqs = new ArrayList<>();
    public final List<String> payloads = new ArrayList<>();
    public final List<String> metas = new ArrayList<>();
    public int heartbeats;
    public boolean completed;
    public boolean aborted;

    @Override
    public void write(String frameType, long seq, String payload, String metaJson) {
        frameTypes.add(frameType);
        seqs.add(seq);
        payloads.add(payload);
        metas.add(metaJson);
    }

    @Override
    public void writeHeartbeat() {
        heartbeats++;
    }

    @Override
    public void complete() {
        completed = true;
    }

    @Override
    public boolean isAborted() {
        return aborted;
    }
}
