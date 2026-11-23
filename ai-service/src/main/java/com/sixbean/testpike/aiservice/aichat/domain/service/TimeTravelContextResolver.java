package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.aichat.domain.component.TimeTravelContext;
import com.sixbean.testpike.aiservice.aichat.infrastructure.remote.ExecStepEvidenceRemote;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExecTaskResultRemote;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Time-travel context for the batch-detail replay capsule (AO batch
 * knife P5): a dialog turn pinned to one execution task carries the step
 * timeline (stepNo / action / status / wall-clock duration) plus the six
 * evidence-slot presence marks, so "why was step N slow or failing" can be
 * answered from the real event flow and the answer cites the step number the
 * client uses to locate the frame.
 * <p>Posture notes:
 * <ul>
 * <li>presigned evidence URLs never enter the prompt — only the presence
 * of each slot (the browser surface reads the urls through the admin
 * bridge it is already logged into);</li>
 * <li>an unreadable timeline degrades honestly: the disclaimer line itself
 * rides the prompt, so the model says it cannot locate steps instead of
 * inventing step numbers;</li>
 * (the same no-fabrication line the evidence panes hold);</li>
 * <li>the block is prompt-weight bounded: at most {@value #MAX_STEP_LINES}
 * step lines, head + tail with an explicit omission count;</li>
 * <li>the segment check stays where it is: the orchestrator only asks for a
 * timeline when the pointer already passed the diagnosis project-slice
 * gate (H1), so this resolver never re-reads the task row for that.</li>
 * </ul>
 */
@Service
public class TimeTravelContextResolver {

    /** Prompt-weight bound: step lines rendered at most (head + tail). */
    static final int MAX_STEP_LINES = 40;

    /** Fixed slot order — the same six-pane contract the browser merges. */
    private static final String[] SLOT_LABELS = {"截图", "视图树", "日志", "录屏", "弱网", "性能"};

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;

    public TimeTravelContextResolver(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Resolve the replay context for one pinned task pointer; null = no
     * pointer, no outbound call, {@link TimeTravelContext#NONE}.
     */
    public TimeTravelContext resolve(Long taskId) {
        if (taskId == null) {
            return TimeTravelContext.NONE;
        }
        ExecTaskResultRemote task;
        try {
            task = coreGateway.getExecTaskResult(taskId);
        } catch (ServiceException unreadable) {
            return disclaimer(taskId, unreadable.getMessage());
        } catch (RuntimeException doorDown) {
            return disclaimer(taskId, "核心服务未响应");
        }
        JsonNode timeline;
        try {
            String raw = task.stepsTimeline();
            if (raw == null || raw.isBlank()) {
                return disclaimer(taskId, "该任务没有回传步骤时间线");
            }
            timeline = objectMapper.readTree(raw);
        } catch (Exception unparsable) {
            return disclaimer(taskId, "步骤时间线无法解析");
        }
        if (!timeline.isArray() || timeline.isEmpty()) {
            return disclaimer(taskId, "该任务没有回传步骤时间线");
        }
        Map<Integer, ExecStepEvidenceRemote> evidence = evidenceByStep(taskId);
        StringBuilder block = new StringBuilder(1024)
                .append("[T] 执行任务 #").append(taskId).append(" 回放时间线（").append(timeline.size())
                .append("步）。回答\"第几步为何慢/挂\"时引用行首的步号，客户端按步号定位回放帧；")
                .append("证据槽标\"缺\"表示执行器未上传，不等于该步没有现场。\n");
        appendStepLines(block, timeline, evidence);
        return TimeTravelContext.of(block.toString());
    }

    private Map<Integer, ExecStepEvidenceRemote> evidenceByStep(long taskId) {
        Map<Integer, ExecStepEvidenceRemote> byStep = new HashMap<>();
        try {
            for (ExecStepEvidenceRemote row : coreGateway.taskStepEvidence(taskId)) {
                byStep.put(row.stepNo(), row);
            }
        } catch (RuntimeException doorDown) {
            // the timeline itself still lands (presigned door ≠ task door)
        }
        return byStep;
    }

    private void appendStepLines(StringBuilder block, JsonNode timeline,
                                 Map<Integer, ExecStepEvidenceRemote> evidence) {
        int total = timeline.size();
        if (total <= MAX_STEP_LINES) {
            for (int i = 0; i < total; i++) {
                block.append(stepLine(timeline.get(i), i, evidence));
            }
            return;
        }
        int head = MAX_STEP_LINES / 2;
        for (int i = 0; i < head; i++) {
            block.append(stepLine(timeline.get(i), i, evidence));
        }
        block.append("- 共").append(total).append("步，中间省略 ")
                .append(total - MAX_STEP_LINES).append(" 步…\n");
        for (int i = total - head; i < total; i++) {
            block.append(stepLine(timeline.get(i), i, evidence));
        }
    }

    private String stepLine(JsonNode step, int index, Map<Integer, ExecStepEvidenceRemote> evidence) {
        int stepNo = step.path("stepNo").isNumber() ? step.path("stepNo").asInt() : index + 1;
        String action = step.path("action").asText("(无动作名)");
        StringBuilder line = new StringBuilder(160)
                .append("- 第").append(stepNo).append("步 ").append(action)
                .append(' ').append(statusLabel(step));
        Long duration = durationMs(step);
        if (duration != null) {
            line.append(" 耗时 ").append(duration).append("ms");
        } else {
            line.append(" 耗时未知");
        }
        line.append(' ').append(evidenceMark(evidence.get(stepNo)));
        return line.append('\n').toString();
    }

    /**
 * Derive one step's wall-clock duration from the shapes the executors
 * actually send (per the 03 protocol, verified against
 * executor/app_worker.py and executor/driver/webdriver.py):
 * <ol>
 * <li>APP rows carry {@code durationMs} as the per-step authority;</li>
 * <li>APP rows also carry {@code tsStart}/{@code tsEnd} as epoch
 * SECONDS (float) — never read as milliseconds;</li>
 * <li>WEB_DRIVER rows carry {@code startAt}/{@code endAt} as local ISO
 * strings whose UTC offset has no colon ({@code +0800}, python %z),
 * so the offset is normalised before parsing.</li>
 * </ol>
 */
    static Long durationMs(JsonNode step) {
        if (step.path("durationMs").isNumber()) {
            return step.path("durationMs").asLong();
        }
        JsonNode start = step.path("tsStart");
        JsonNode end = step.path("tsEnd");
        if (start.isNumber() && end.isNumber()) {
            return Math.round((end.asDouble() - start.asDouble()) * 1000);
        }
        String startAt = text(step, "startAt");
        String endAt = text(step, "endAt");
        if (startAt != null && endAt != null) {
            java.time.OffsetDateTime from = parseIsoOffsetSeconds(startAt);
            java.time.OffsetDateTime to = parseIsoOffsetSeconds(endAt);
            if (from != null && to != null) {
                return java.time.Duration.between(from, to).toMillis();
            }
        }
        return null;
    }

    /**
 * Parse the executor's ISO stamp; {@code +0800} gains its colon before
 */
    private static java.time.OffsetDateTime parseIsoOffsetSeconds(String raw) {
        String normalized = raw.replace(" ", "T");
        java.util.regex.Matcher offset = ISO_COLONLESS_OFFSET.matcher(normalized);
        if (offset.find()) {
            normalized = offset.replaceFirst("$1$2:$3");
        }
        try {
            return java.time.OffsetDateTime.parse(normalized);
        } catch (RuntimeException unparsable) {
            return null;
        }
    }

    private static final java.util.regex.Pattern ISO_COLONLESS_OFFSET =
            java.util.regex.Pattern.compile("([+-])(\\d{2})(\\d{2})$");

    private static String text(JsonNode step, String field) {
        JsonNode node = step.path(field);
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static String evidenceMark(ExecStepEvidenceRemote row) {
        String[] keys = row == null ? new String[0] : new String[]{
                row.screenshotKey(), row.viewTreeKey(), row.logTailKey(),
                row.recordKey(), row.netTimelineKey(), row.perfTimelineKey()};
        StringBuilder mark = new StringBuilder("证据[");
        for (int i = 0; i < SLOT_LABELS.length; i++) {
            mark.append(i > 0 ? " " : "")
                    .append(SLOT_LABELS[i]).append('=')
                    .append(i < keys.length && keys[i] != null && !keys[i].isBlank() ? "有" : "缺");
        }
        return mark.append(']').toString();
    }

    /**
 * Status word from either producer shape: WEB_DRIVER rows stamp
 * {@code status} (ok/fail/skipped), APP rows stamp {@code result}
 */
    private static String statusLabel(JsonNode step) {
        String raw = step.path("status").asText(null);
        if (raw == null || raw.isBlank()) {
            raw = step.path("result").asText(null);
        }
        if (raw == null || raw.isBlank()) {
            return "状态未知";
        }
        return switch (raw) {
            case "ok", "pass" -> "成功";
            case "fail" -> "失败";
            case "skipped" -> "跳过";
            default -> raw;
        };
    }

    private static TimeTravelContext disclaimer(long taskId, String why) {
        return TimeTravelContext.of("[T] 执行任务 #" + taskId + " 的回放时间线读不到（" + why
                + "）。用户问第几步为何慢/挂时，如实说明无法定位步骤现场，基于失败诊断与本条问题回答，禁止编造步号。");
    }
}
