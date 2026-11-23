package com.sixbean.testpike.admin.ai.app.service;

import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreDocumentClient;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameWriter;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Extraction tail-f pump : streams one document's live process
 * events to the browser over the shared SSE frame writer, so the operator of a
 * multi-hour extraction watches each chunk call, token digest and failure
 * cause land as they happen instead of discovering them on a 5-second
 * full-page refresh.
 * <p>Single-source by design (R40): since the step-generation drawer arrived
 * the loop itself lives in the shared {@link ProcessEventPump} — cursor
 * advancement, heartbeat, state probe throttling, the final drain and the
 * honest failure frames are written exactly once for every job face; this
 * class only adapts the document face: window from the core read endpoint,
 * settled probe = the document task state leaving "extracting".
 * <p>Blocking by design — the controller runs this on one virtual thread per
 * open drawer.
 */
@Service
public class ExtractionEventStreamBridge {

    /** Document task state meaning "extraction in flight" (3 = extracting). */
    private static final int STATUS_EXTRACTING = 3;

    private final CoreDocumentClient coreClient;
    private final ProcessEventPump pump;

    public ExtractionEventStreamBridge(CoreDocumentClient coreClient,
                                        CoreAiChatClient configDoor) {
        this.coreClient = coreClient;
        this.pump = new ProcessEventPump(configDoor);
    }

    /**
     * Tail one document's process events from {@code cursor} (the last row id
     * the viewer already saw; 0 replays the whole live run) until the document
     * settles, the wire is aborted, or the quiet/idle guards give up.
     */
    public void stream(long documentId, long cursor, StreamFrameWriter writer) {
        pump.tail("提取",
                c -> coreClient.extractionEvents(documentId, c, null).stream()
                        .map(row -> new ProcessEventRow(
                                row.cursor(), row.level(), row.summary(), row.detail(),
                                row.landedAt()))
                        .collect(Collectors.toList()),
                () -> coreClient.extraction(documentId, 1, 1).status() != STATUS_EXTRACTING,
                cursor, writer);
    }
}
