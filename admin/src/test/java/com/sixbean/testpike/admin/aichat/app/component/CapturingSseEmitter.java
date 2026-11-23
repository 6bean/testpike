package com.sixbean.testpike.admin.aichat.app.component;

import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter.DataWithMediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Test double that keeps every block the frame writer hands to the servlet
 * transport as one joined string, so the wire format (field names, separators,
 * padding) is asserted on the exact bytes the browser leg will read. Lives
 * before {@code initialize()} is ever called: an unstarted emitter buffers its
 * sends, and the captured block is the same serialization the real transport
 * writes. Shared by the SSE wire-shape tests of this package.
 */
final class CapturingSseEmitter extends SseEmitter {

    private final List<String> blocks = new ArrayList<>();

    @Override
    public void send(SseEventBuilder builder) throws IOException {
        StringBuilder joined = new StringBuilder();
        for (DataWithMediaType entry : builder.build()) {
            joined.append(entry.getData());
        }
        blocks.add(joined.toString());
    }

    /** The blocks captured so far, in send order. */
    List<String> blocks() {
        return Collections.unmodifiableList(blocks);
    }

    /** The single block captured by a one-frame write (fails fast on 0 or 2+). */
    String singleBlock() {
        if (blocks.size() != 1) {
            throw new IllegalStateException("expected exactly one send, got " + blocks);
        }
        return blocks.get(0);
    }
}
