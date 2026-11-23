package com.sixbean.testpike.aiservice.llm.infrastructure.client;

import java.io.IOException;
import java.io.InputStream;

/**
 * Test double for the JDK response-body stream when the stream guard closes
 * the body while the reader thread is parked mid-read. Field attribution
 * (2026-11-17, extraction docs 9077-9079): the cancellation wakes the parked
 * read with the thread interrupt flag still set plus an IOException, and that
 * residue poisoned every later JDBC fetch and retry on the same extraction
 * worker. This double reproduces exactly that hand-off so the hygiene fix in
 * OpenAiCompatClient has a deterministic failing test.
 */
final class StreamInterruptingOnCloseBody extends InputStream {

    private boolean closed;

    @Override
    public int read() throws IOException {
        return read(new byte[1], 0, 1);
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        synchronized (this) {
            while (!closed) {
                try {
                    wait(20);
                } catch (InterruptedException e) {
                    // A pre-existing cancellation must also surface as residue
                    // plus failure, same shape as the JDK behaviour.
                    Thread.currentThread().interrupt();
                    throw new IOException("parked read interrupted", e);
                }
            }
        }
        // Mirror the observed poisoning: the guard's close leaves the current
        // thread interrupted and fails the blocked read.
        Thread.currentThread().interrupt();
        throw new IOException("response body cancelled by stream guard");
    }

    @Override
    public void close() {
        synchronized (this) {
            closed = true;
            notifyAll();
        }
    }
}
