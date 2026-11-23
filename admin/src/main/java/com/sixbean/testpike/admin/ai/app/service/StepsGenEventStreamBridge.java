package com.sixbean.testpike.admin.ai.app.service;

import com.sixbean.testpike.admin.ai.api.dto.AiTaskEventView;
import com.sixbean.testpike.admin.ai.infrastructure.remote.client.CoreAiTaskClient;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameWriter;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * Steps-generation tail-f pump : streams one AI task's
 * live process events (per-shard progress of a step-group run) to the browser
 * over the shared cursor-window door, so the operator watches each shard
 * result and failure cause land as they happen instead of refreshing the task
 * list.
 * <p>Single-source by design: the loop itself is the shared
 * {@link ProcessEventPump} — the same one the document extraction drawer
 * rides (R40, no second pump copy); this class only adapts the two job faces:
 * the cursor window comes from the core ledger door
 * {@code GET /internal/ai-tasks/{taskId}/process-events}, and the settled
 * probe reads the task's status (1 pending / 2 running are in flight,
 * 3 done / 4 failed seal the stream after a final drain).
 * <p>Blocking by design — the controller runs this on one virtual thread per
 * open drawer.
 */
@Service
public class StepsGenEventStreamBridge {

    /** Lowest task status meaning the run has settled (3 = done; 4 = failed). */
    private static final int STATUS_SETTLED_FLOOR = 3;

    private final CoreAiTaskClient coreClient;
    private final ProcessEventPump pump;

    public StepsGenEventStreamBridge(CoreAiTaskClient coreClient,
                                     CoreAiChatClient configDoor) {
        this.coreClient = coreClient;
        this.pump = new ProcessEventPump(configDoor);
    }

    /**
     * Tail one task's process events from {@code cursor} (the last row id the
     * viewer already saw; 0 replays the whole live run) until the task
     * settles, the wire is aborted, or the quiet/idle guards give up.
     */
    public void stream(long taskId, long cursor, StreamFrameWriter writer) {
        pump.tail("生成",
                c -> coreClient.taskProcessEvents(taskId, c, null).stream()
                        .map(StepsGenEventStreamBridge::toRow)
                        .collect(Collectors.toList()),
                () -> coreClient.getTask(taskId).status() >= STATUS_SETTLED_FLOOR,
                cursor, writer);
    }

    private static ProcessEventRow toRow(AiTaskEventView view) {
        return new ProcessEventRow(view.cursor(), view.level(), view.summary(),
                view.detail(), view.landedAt());
    }
}
