package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportNotifyService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Component
public class PerfRunResultFinalizer {

    private static final Logger log = LoggerFactory.getLogger(PerfRunResultFinalizer.class);

    
    private static final Set<Integer> EXPLICIT_FINAL_STATUSES = Set.of(2, 3, 4);

    private final PerfMetricService perfMetricService;
    private final PerfRunService perfRunService;
    private final PerfReportNotifyService perfReportNotifyService;
    private final ObjectMapper objectMapper;
    private final PerfWireFrameCodec wireFrameCodec;
    private final PerfConfigReader perfConfigReader;
    private final NamedParameterJdbcTemplate jdbc;

    public PerfRunResultFinalizer(PerfMetricService perfMetricService,
                                  PerfRunService perfRunService,
                                  PerfReportNotifyService perfReportNotifyService,
                                  ObjectMapper objectMapper,
                                  PerfWireFrameCodec wireFrameCodec,
                                  PerfConfigReader perfConfigReader,
                                  NamedParameterJdbcTemplate jdbc) {
        this.perfMetricService = perfMetricService;
        this.perfRunService = perfRunService;
        this.perfReportNotifyService = perfReportNotifyService;
        this.objectMapper = objectMapper;
        this.wireFrameCodec = wireFrameCodec;
        this.perfConfigReader = perfConfigReader;
        this.jdbc = jdbc;
    }

    
    public void onResultSubmitted(int taskType, long projectId, String actionJson,
                                  ExecResultInput input) {
        if (taskType != 5) {
            return;
        }
        long runId = extractRunId(actionJson);
        if (runId <= 0) {
            log.warn("压测任务信封缺 runId（脏单）——采集数据不回写，任务收口不受影响（项目 {}）",
                    projectId);
            return;
        }
        storeQuietly("指标帧", projectId, runId, () -> {
            List<PerfMetricFrame> frames = wireFrameCodec.toFrames(input.perfFrames());
            if (!frames.isEmpty()) {
                perfMetricService.recordFrames(projectId, runId, frames);
            }
        });
        storeQuietly("事件流", projectId, runId, () -> {
            List<PerfEventRow> events = toEvents(input.perfEvents());
            if (!events.isEmpty()) {
                perfMetricService.recordEvents(projectId, runId, events);
            }
        });
        int status = mapStatus(input);
        // Terminal coverage truth check : if the seconds the run actually
        // pressed did not all reach core (tail past the hot-updatable tolerance is
        // missing), the curve gets a data-loss event + an annotated stop reason —
        // never old values silently impersonating a complete run.
        PerfDataLossFinding loss = judgeCoverage(projectId, runId);
        if (loss.lost()) {
            storeQuietly("data-loss 事件", projectId, runId, () -> perfMetricService.recordEvents(
                    projectId, runId, List.of(new PerfEventRow(
                            loss.coverageMaxSecond(), "data-loss",
                            "尾部采样缺失 " + loss.missingSeconds() + " 秒（实际压至第 " + loss.expectedSeconds()
                                    + " 秒，曲线止于第 " + loss.coverageMaxSecond()
                                    + " 秒）——末段曲线非完整数据，判读请以此为前提"))));
        }
        storeQuietly("运行结束落库", projectId, runId, () -> {
            perfRunService.finish(projectId, runId, status, stopReason(input, status, loss));
            // GB-3 automatic SLA red-light alert: fired only after THIS callback flipped
            // the run to terminal (replays rejected above never re-alert; the send itself
            // is after-commit deferred and dedup-guarded, green runs stay silent).
            perfReportNotifyService.sendSlaBreachCard(projectId, runId);
        });
    }

 
    private void storeQuietly(String facet, long projectId, long runId, Runnable action) {
        try {
            action.run();
        } catch (ServiceException e) {
            log.warn("压测运行 {}（项目 {}）{}回写被拒（{}）——任务收口不受影响",
                    runId, projectId, facet, e.getMessage());
        }
    }

 
    private int mapStatus(ExecResultInput input) {
        Integer explicit = input.perfStatus();
        if (explicit != null && EXPLICIT_FINAL_STATUSES.contains(explicit)) {
            return explicit;
        }
        return "pass".equals(input.result()) ? 2 : 4;
    }

    /**
 * Terminal coverage judge : compares
 * the deepest stored aggregate second against the span the run actually
 * pressed (declared duration from the pinned snapshot, capped by wall-clock
 * elapsed). Missing tail beyond the hot-updatable tolerance = data loss.
 * No snapshot duration claim / no stored frames = none (honest no-claim
 * posture — the empty-curve faces already say what they can say).
 */
    private PerfDataLossFinding judgeCoverage(long projectId, long runId) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT pressure_snapshot::text AS snapshot, "
                        + "extract(epoch from (now() - started_at))::bigint AS elapsed"
                        + " FROM tp_perf_run WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId));
        if (rows.isEmpty()) {
            return PerfDataLossFinding.none();
        }
        double declared = PerfBudgetPolicy.durationSecondsOfSnapshot(objectMapper,
                String.valueOf(rows.get(0).get("snapshot")));
        if (declared <= 0) {
            return PerfDataLossFinding.none();
        }
        Integer maxStored = perfMetricService.maxAggregateSecondOffset(projectId, runId);
        if (maxStored == null) {
            return PerfDataLossFinding.none();
        }
        long elapsed = ((Number) rows.get(0).get("elapsed")).longValue();
        long expectedSeconds = Math.min((long) Math.ceil(declared), elapsed);
        long missing = expectedSeconds - (maxStored + 1L);
        if (missing <= perfConfigReader.coverageToleranceSeconds()) {
            return new PerfDataLossFinding(false, maxStored, expectedSeconds, Math.max(missing, 0));
        }
        return new PerfDataLossFinding(true, maxStored, expectedSeconds, missing);
    }

    /** Stop-reason in human terms: normal end = default wording; breaker/manual =
     *  executor errorDetail under the shared phone/e-mail spectrum (U-10 ingest
     *  point: stop reasons quote executor free text that can carry PII, masked
     *  before it lands in the 500-char column); when data loss is
     *  judged, the missing tail seconds ride the stop reason too — truth first,
     *  never a complete-curve illusion. */
    private String stopReason(ExecResultInput input, int status, PerfDataLossFinding loss) {
        String reason;
        if (status == 2) {
            reason = "压测运行正常结束";
        } else {
            String detail = input.errorDetail();
            reason = detail == null || detail.isBlank()
                    ? (status == 3 ? "熔断触发自动停压" : "压测运行终止（未提供停因）")
                    : DesensitizeUtils.maskText(detail);
        }
        if (loss.lost()) {
            reason = reason + "（尾部采样缺失 " + loss.missingSeconds() + " 秒，曲线非完整数据）";
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }

    
    private long extractRunId(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return 0;
        }
        try {
            return objectMapper.readTree(actionJson).path("runId").asLong(0);
        } catch (Exception e) {
            return 0;
        }
    }

    /**
 * Frame mapping moved to PerfWireFrameCodec ( b2, red-line-6: the heartbeat
 * partial channel and this result channel share one codec, buckets can never
 * drift between the two). */
    private List<PerfEventRow> toEvents(Object raw) {
        List<PerfEventRow> events = new ArrayList<>();
        for (Map<String, Object> row : mapRows(raw)) {
            events.add(new PerfEventRow(
                    intValue(row.get("secondOffset"), 0),
                    row.get("eventKind") == null ? null : String.valueOf(row.get("eventKind")),
                    row.get("detail") == null ? null : String.valueOf(row.get("detail"))));
        }
        return events;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> mapRows(Object raw) {
        if (!(raw instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> rows = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                rows.add((Map<String, Object>) map);
            }
        }
        return rows;
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }
}
