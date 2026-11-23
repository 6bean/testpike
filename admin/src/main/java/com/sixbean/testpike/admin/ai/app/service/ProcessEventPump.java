package com.sixbean.testpike.admin.ai.app.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.aichat.app.component.StreamFrameWriter;
import com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.LongFunction;

/**
 * The tail-f pump loop, single-sourced: one implementation rides under every
 * long-running AI job drawer that tails a process event ledger (document
 * extraction since , step generation added here), so cursor
 * advancement, heartbeat throttling, state probing, the final drain before
 * the seal and the quiet-milestone guard are written exactly once.
 * <p>The wire face stays the dialog batch's StreamFrameWriter (never a second
 * SSE writer), the hot keys ride the same K8 door as the dialog pump
 * ({@code ai.stream.*}), and the whole loop stays blockingly simple — the
 * controller runs one virtual thread per open drawer.
 * <p>Closing semantics: a run has no terminal frame in the event ledger, so
 * the pump probes the job's task state on a throttle. Once the state leaves
 * the in-flight set, one last cursor drain lands the trailing events before
 * the seal, so a viewer reconnecting later continues from the same tail row.
 * Quiet is not a failure: a long model call legitimately idles for minutes,
 * so silence past the idle interval only lands a step-level "still running"
 * milestone and the stream keeps tailing, up to the hot-configured
 * single-window cap — the one designed give-up — which seals honestly and
 * says a reconnect continues from the same row.
 * <p>Door outages never fail silently: an unreadable window or state ends the
 * stream with one honest error frame naming the subject and the cause.
 */
public final class ProcessEventPump {

    /** JSON side channel carrying the true ledger landing time with the
     *  detail payload (user report 2026-11-20: SSE frames without a timestamp
     *  made the browser stamp every replayed row with the arrival clock, so a
     *  reconnect showed one identical time for the whole history). Hosts
     *  without a landing time keep the legacy plain-detail meta byte-for-byte. */
    private static final ObjectMapper META_MAPPER = new ObjectMapper();

    /** Frame type of a pump-side degradation, matching the ledger's error level. */
    private static final String FRAME_ERROR = "error";

    /** Frame type of pump-side progress notes — the closed set has no info level. */
    private static final String FRAME_STEP = "step";

    private final CoreAiChatClient configDoor;

    public ProcessEventPump(CoreAiChatClient configDoor) {
        this.configDoor = configDoor;
    }

    /**
 * Tail one job's process events from {@code cursor} (the last row id the
 * viewer already saw; 0 replays the whole live run) until the run settles
 * ({@code settledProbe} answers true), the wire is aborted, or the
 * quiet/idle guards give up. {@code window} fetches the next rows landed
 * after a cursor; {@code subject} names the job face inside the honest
 */
    public void tail(String subject, LongFunction<List<ProcessEventRow>> window,
                    BooleanSupplier settledProbe, long cursor, StreamFrameWriter writer) {
        long pollMillis = Math.max(50L,
                configDoor.readConfigInt("ai.stream.poll-interval-ms", 200));
        long heartbeatMillis = Math.max(1L,
                configDoor.readConfigInt("ai.stream.heartbeat-seconds", 10)) * 1000L;
        long probeMillis = Math.max(200L,
                configDoor.readConfigInt("ai.stream.status-probe-interval-ms", 1000));
        long idleMillis = Math.max(5L,
                configDoor.readConfigInt("ai.stream.idle-timeout-seconds", 300)) * 1000L;
        long tailMaxMillis = Math.max(10L,
                configDoor.readConfigInt("ai.stream.tail-max-seconds", 3600)) * 1000L;

        long openedAt = System.currentTimeMillis();
        long lastFrameAt = 0L;
        long lastBeatAt = openedAt;
        long lastProbeAt = 0L;
        while (!writer.isAborted()) {
            List<ProcessEventRow> rows;
            try {
                rows = window.apply(cursor);
            } catch (RuntimeException doorDown) {
                writer.write(FRAME_ERROR, cursor + 1,
                        "无法读取" + subject + "过程事件：" + doorDown.getMessage(), null);
                writer.complete();
                return;
            }
            long now = System.currentTimeMillis();
            if (now - openedAt >= tailMaxMillis) {
                // The one designed give-up: land the trailing rows first, then
                // seal with an honest note that a reconnect continues here.
                // Reaching the cap is not a failure, so it rides the step
                // level, never the error level. No subject prefix: same
                // label-doubling reason as the quiet milestone above.
                drainOnce(window, cursor, writer);
                writer.write(FRAME_STEP, cursor + 1,
                        "实时跟随已达单窗上限 " + (tailMaxMillis / 1000) + " 秒，本窗到此结束；"
                                + "重连后可继续跟随", null);
                writer.complete();
                return;
            }
            if (!rows.isEmpty()) {
                for (ProcessEventRow row : rows) {
                    writer.write(row.level(), row.cursor(), row.summary(), frameMeta(row));
                    cursor = row.cursor();
                }
                lastFrameAt = now;
                lastBeatAt = now;
                continue;
            }
            if (now - lastBeatAt >= heartbeatMillis) {
                writer.writeHeartbeat();
                lastBeatAt = now;
            }
            // The first pass probes at once: a run that already settled must
            // seal the stream instead of parking a virtual thread.
            if (now - lastProbeAt >= probeMillis) {
                lastProbeAt = now;
                boolean settled;
                try {
                    settled = settledProbe.getAsBoolean();
                } catch (RuntimeException doorDown) {
                    writer.write(FRAME_ERROR, cursor + 1,
                            "无法读取" + subject + "任务状态：" + doorDown.getMessage(), null);
                    writer.complete();
                    return;
                }
                if (settled) {
                    drainOnce(window, cursor, writer);
                    writer.complete();
                    return;
                }
            }
            long quietSince = lastFrameAt > 0 ? lastFrameAt : openedAt;
            if (now - quietSince >= idleMillis) {
                // Quiet while the run is still in flight is a legitimate phase
                // (one long model call idles for minutes), not a failure — a
                // real break needs a door outage, proven by the error frames
                // above. Land a milestone so the viewer sees the pump is
                // alive, reset the quiet clock, and keep tailing. The body
                // opens without the subject: the drawer already prints a
                // "step" level label before it, so a subject that starts with
                // the same word rendered doubled on screen (recap shots).
                writer.write(FRAME_STEP, cursor + 1,
                        "已超过 " + (idleMillis / 1000) + " 秒没有新事件，"
                                + "任务仍在进行，将继续跟随", null);
                lastFrameAt = now;
            }
            sleepQuietly(pollMillis);
        }
    }

    /** Landing pass before the seal: the final events of a run often arrive
     *  between the last window pull and the state probe; missing them would
     *  force a reconnect just to read the tail. */
    private void drainOnce(LongFunction<List<ProcessEventRow>> window,
                           long cursor, StreamFrameWriter writer) {
        try {
            for (ProcessEventRow row : window.apply(cursor)) {
                writer.write(row.level(), row.cursor(), row.summary(), frameMeta(row));
            }
        } catch (RuntimeException doorDown) {
            // The stream is sealing anyway — the settled state itself already
            // reached the viewer through the page, a lost tail row must not
            // turn a clean close into an error face.
            writer.write(FRAME_ERROR, cursor + 1,
                    "收尾事件读取失败：" + doorDown.getMessage(), null);
        }
    }

    /** Meta wire shape: plain detail (legacy) unless the row carries a
     *  landing time, then {tpTS, d} JSON — the browser unwraps it back into
     *  detail + true landedAt. */
    private static String frameMeta(ProcessEventRow row) {
        if (row.landedAt() == null) {
            return row.detail();
        }
        try {
            java.util.Map<String, String> envelope = new java.util.LinkedHashMap<>();
            envelope.put("tpTS", row.landedAt());
            if (row.detail() != null) {
                envelope.put("d", row.detail());
            }
            return META_MAPPER.writeValueAsString(envelope);
        } catch (com.fasterxml.jackson.core.JsonProcessingException malformed) {
            // Never lose a row for a meta hiccup: the detail rides plain.
            return row.detail();
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
