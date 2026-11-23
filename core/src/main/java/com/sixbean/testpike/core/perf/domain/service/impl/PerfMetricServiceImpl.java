package com.sixbean.testpike.core.perf.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.perf.api.dto.PerfLiveCurveView;
import com.sixbean.testpike.core.perf.domain.component.PerfErrorClassCount;
import com.sixbean.testpike.core.perf.domain.component.PerfEventRow;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * <p>Replay idempotency = ON CONFLICT DO NOTHING on the run x second_offset x step_key
 * unique anchor; duplicate payloads add zero rows and never overwrite the first frame's values
 * (03 protocol result-channel replay posture).
 */
@Service
public class PerfMetricServiceImpl implements PerfMetricService {

    /**
 * Per-batch frame ceiling: an over-limit batch is refused WHOLE and the
 * executor chunks every shipped row to this projected value.
 * Caliber = single-dimension per-second sampling for 2 hours; a multi-step
 * run emits one aggregate plus one per-step frame each second
 * (frames = seconds x (1 + steps)), so it crosses the ceiling earlier and
 * is served by chunking, never by raising this gate.
 * : public + projected to the executor as perf.maxShipFrames
 * (single numeric source — the executor must not keep its own copy of this
 * ceiling; its shipped default only backstops legacy envelopes).
 */
    public static final int MAX_BATCH_FRAMES = 7200;
    
    private static final int MAX_EVENT_DETAIL_CHARS = 4000;

    private static final String FRAME_SELECT = """
            id, run_id, project_id, second_offset, rps, p95_ms, error_rate,
            ttft_ms, itl_ms, tokens_per_sec, auth_ms, auth_count,
            req_count, fail_count, duration_sum_ms, hist, error_class, step_key,
            host_stats, created_at
            """;

    private static final String EVENT_SELECT = """
            id, run_id, project_id, second_offset, event_kind, detail, created_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final PerfConfigReader configReader;

    public PerfMetricServiceImpl(NamedParameterJdbcTemplate jdbc, PerfConfigReader configReader) {
        this.jdbc = jdbc;
        this.configReader = configReader;
    }

    @Override
    public int recordFrames(long projectId, long runId, List<PerfMetricFrame> frames) {
        requireRunningRun(projectId, runId);
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        if (frames.size() > MAX_BATCH_FRAMES) {
            throw new ServiceException("指标帧单批 " + frames.size() + " 帧超过上限 "
                    + MAX_BATCH_FRAMES + "（2 小时秒采样），请执行器侧分批回传",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<PerfMetricFrame> legal = new ArrayList<>(frames.size());
        Set<String> anchors = new HashSet<>();
        for (int i = 0; i < frames.size(); i++) {
            PerfMetricFrame f = frames.get(i);
            int rowNo = i + 1;
            if (f.secondOffset() < 0) {
                throw new ServiceException("第 " + rowNo + " 帧秒偏移非法（" + f.secondOffset()
                        + "），秒偏移必须从 0 起计数", BizErrorCode.VALIDATION_ERROR);
            }
            if (f.errorRate() < 0 || f.errorRate() > 1) {
                throw new ServiceException("第 " + rowNo + " 帧错误率非法（" + f.errorRate()
                        + "），错误率取值范围 0 到 1", BizErrorCode.VALIDATION_ERROR);
            }
            if (f.reqCount() != null && f.reqCount() < 0) {
                throw new ServiceException("第 " + rowNo + " 帧请求数非法（" + f.reqCount()
                        + "），请求数不能为负", BizErrorCode.VALIDATION_ERROR);
            }
            if (f.failCount() != null && f.reqCount() != null && f.failCount() > f.reqCount()) {
                throw new ServiceException("第 " + rowNo + " 帧失败数 " + f.failCount()
                        + " 超过请求数 " + f.reqCount() + "，疑似执行器归帧错乱",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (f.durationSumMs() != null && f.durationSumMs() < 0) {
                throw new ServiceException("第 " + rowNo + " 帧耗时合计非法（" + f.durationSumMs()
                        + "），响应耗时不能为负", BizErrorCode.VALIDATION_ERROR);
            }
            String anchor = frameAnchor(f.secondOffset(), f.stepKey());
            if (!anchors.add(anchor)) {
                throw new ServiceException("第 " + rowNo + " 帧秒偏移 " + f.secondOffset()
                        + " 步骤「" + displayStep(f.stepKey()) + "」与本批前序帧重复，请每帧按秒与步骤唯一上报",
                        BizErrorCode.VALIDATION_ERROR);
            }
            legal.add(f);
        }
        Map<String, Double> existing = new HashMap<>();
        jdbc.queryForList("SELECT second_offset, step_key, rps FROM tp_perf_metric "
                        + "WHERE run_id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId))
                .forEach(row -> existing.put(frameAnchor(
                        ((Number) row.get("second_offset")).intValue(),
                        String.valueOf(row.getOrDefault("step_key", ""))),
                        ((Number) row.get("rps")).doubleValue()));
        for (int i = 0; i < legal.size(); i++) {
            PerfMetricFrame f = legal.get(i);
            Double prev = existing.get(frameAnchor(f.secondOffset(), f.stepKey()));
            if (prev != null && Double.compare(prev, f.rps()) != 0) {
                throw new ServiceException("第 " + (i + 1) + " 帧秒偏移 " + f.secondOffset()
                        + " 步骤「" + displayStep(f.stepKey()) + "」已有上报值 " + prev
                        + "，重放内容不一致（吞吐 " + f.rps() + "），疑似跨运行错投或补报旧秒，拒收",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        List<MapSqlParameterSource> params = new ArrayList<>(legal.size());
        for (PerfMetricFrame f : legal) {
            params.add(new MapSqlParameterSource()
                    .addValue("run", runId).addValue("p", projectId)
                    .addValue("off", f.secondOffset()).addValue("rps", f.rps())
                    .addValue("p95", f.p95Ms()).addValue("err", f.errorRate())
                    .addValue("ttft", f.ttftMs()).addValue("itl", f.itlMs())
                    .addValue("tps", f.tokensPerSec())
                    .addValue("authMs", f.authMs()).addValue("authCount", f.authCount())
                    .addValue("reqCount", f.reqCount()).addValue("failCount", f.failCount())
                    .addValue("durationSum", f.durationSumMs())
                    .addValue("hist", jsonOrNull(f.hist()))
                    .addValue("errorClass", jsonOrNull(f.errorClass()))
                    .addValue("hostStats", jsonOrNull(f.hostStats()))
                    .addValue("stepKey", normalizeStep(f.stepKey())));
        }
        int[] affected = jdbc.batchUpdate("INSERT INTO tp_perf_metric (run_id, project_id, "
                + "second_offset, rps, p95_ms, error_rate, ttft_ms, itl_ms, tokens_per_sec, "
                + "auth_ms, auth_count, req_count, fail_count, duration_sum_ms, hist, error_class, "
                + "step_key, host_stats) "
                + "VALUES (:run, :p, :off, :rps, :p95, :err, :ttft, :itl, :tps, :authMs, :authCount, "
                + ":reqCount, :failCount, :durationSum, CAST(:hist AS jsonb), "
                + "CAST(:errorClass AS jsonb), :stepKey, CAST(:hostStats AS jsonb)) "
                + "ON CONFLICT (run_id, second_offset, step_key) DO NOTHING",
                params.toArray(MapSqlParameterSource[]::new));
        int inserted = 0;
        for (int n : affected) {
            inserted += Math.max(n, 0);
        }
        return inserted;
    }

    /** Idempotency anchor = second x step (mirrors the third column of the unique index;
     *  empty step key = the legacy un-split shape). */
    private static String frameAnchor(int secondOffset, String stepKey) {
        return secondOffset + "#" + normalizeStep(stepKey);
    }

    /** Normalize the step key (null/blank collapse to empty string; NULL would break
     *  the unique-index comparison). */
    private static String normalizeStep(String stepKey) {
        return stepKey == null ? "" : stepKey.trim();
    }

    /** Human-readable step label for rejections (empty key shows as "un-split", never bare quotes). */
    private static String displayStep(String stepKey) {
        String s = normalizeStep(stepKey);
        return s.isEmpty() ? "未分步" : s;
    }

    /** Pre-ingestion normalization for histogram/error-class payloads (blank = NULL; JSON
     *  validity is guarded by the jsonb column type as the second value-range line). */
    private static String jsonOrNull(String raw) {
        return raw == null || raw.isBlank() ? null : raw;
    }

    @Override
    public List<Map<String, Object>> listFrames(long projectId, long runId) {
        return jdbc.queryForList("SELECT " + FRAME_SELECT + " FROM tp_perf_metric "
                        + "WHERE run_id = :id AND project_id = :p ORDER BY second_offset",
                new MapSqlParameterSource("id", runId).addValue("p", projectId));
    }

    @Override
    public List<Map<String, Object>> listAggregateFrames(long projectId, long runId) {
        // Aggregate caliber = un-split frames only (step_key=''); per-step frames of the
        // same second would double-count rps/req if they entered the curve/verdict reads.
        return jdbc.queryForList("SELECT " + FRAME_SELECT + " FROM tp_perf_metric "
                        + "WHERE run_id = :id AND project_id = :p AND step_key = '' "
                        + "ORDER BY second_offset",
                new MapSqlParameterSource("id", runId).addValue("p", projectId));
    }

    /**
 * Live-curve read face . Two defence lines on the 2s running-page poll:
 * ① cursor — only seconds strictly after the last one the caller saw are shipped, so a
 * 4h soak never re-sends 14400 rows per poll; ② adaptive downsample — when the pending
 * window still exceeds the point ceiling, seconds collapse into buckets in SQL
 * (p95=bucket MAX: a latency spike never hides behind averaging; rps=bucket mean;
 * error_rate=request-weighted Σfail/Σreq when bucket counts exist, else the per-second
 * mean — the same O10 weighted caliber the full-run judge uses, curve and verdict
 * never diverge). sampled=true is an honest annotation, never silent thinning.
 * Aggregate caliber (step_key='') and the (run, project) ownership filter match the
 * frames face: a foreign run answers empty, no leak.
 */
    @Override
    public PerfLiveCurveView liveCurve(long projectId, long runId, Integer sinceOffset,
                                       Integer maxPointsOverride) {
        int ceiling = maxPointsOverride != null && maxPointsOverride >= 1
                ? maxPointsOverride
                : configReader.liveCurveMaxPoints();
        String scope = "FROM tp_perf_metric WHERE run_id = :id AND project_id = :p "
                + "AND step_key = ''"
                + (sinceOffset == null ? "" : " AND second_offset > :since");
        MapSqlParameterSource anchor =
                new MapSqlParameterSource("id", runId).addValue("p", projectId);
        if (sinceOffset != null) {
            anchor.addValue("since", sinceOffset);
        }
        Map<String, Object> window = jdbc.queryForMap(
                "SELECT count(*) AS row_total, max(second_offset) AS last_offset " + scope, anchor);
        long total = ((Number) window.get("row_total")).longValue();
        Integer lastOffset = window.get("last_offset") == null
                ? null : ((Number) window.get("last_offset")).intValue();
        if (total == 0) {
            return new PerfLiveCurveView(List.of(), false, 1, null, 0L, ceiling);
        }
        int bucket = (int) Math.ceil((double) total / ceiling);
        if (bucket <= 1) {
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + FRAME_SELECT + " " + scope + " ORDER BY second_offset", anchor);
            return new PerfLiveCurveView(rows, false, 1, lastOffset, total, ceiling);
        }
        anchor.addValue("b", bucket);
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT (second_offset / :b) * :b AS bucket_start,
                       MAX(second_offset) AS second_offset,
                       AVG(rps) AS rps,
                       MAX(p95_ms) AS p95_ms,
                       CASE WHEN SUM(req_count) IS NOT NULL AND SUM(req_count) > 0
                            THEN SUM(fail_count)::numeric / SUM(req_count)
                            ELSE AVG(error_rate) END AS error_rate
                """ + scope + " GROUP BY 1 ORDER BY 1", anchor);
        return new PerfLiveCurveView(rows, true, bucket, lastOffset, total, ceiling);
    }

    /**
 * Terminal coverage probe : deepest stored aggregate second only —
 * the data-loss judge needs one number, not the whole series in memory.
 */
    @Override
    public Integer maxAggregateSecondOffset(long projectId, long runId) {
        List<Integer> rows = jdbc.queryForList("SELECT max(second_offset) FROM tp_perf_metric "
                        + "WHERE run_id = :id AND project_id = :p AND step_key = ''",
                new MapSqlParameterSource("id", runId).addValue("p", projectId), Integer.class);
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
 * Error-class TopN : one SQL expands the jsonb counts of un-split frames
 * (aggregate caliber — step lines carry the same requests and would double-count),
 * sums per class key, descending by count with the class word as the tie-break so
 * the drawer order is stable across reloads. Classes never collected are absent —
 * honest empty beats a fabricated zero wall.
 */
    @Override
    public List<PerfErrorClassCount> errorClassTopN(long projectId, long runId) {
        return jdbc.queryForList("SELECT k AS error_class, SUM((v)::bigint) AS class_count "
                        + "FROM tp_perf_metric, jsonb_each(error_class) AS x(k, v) "
                        + "WHERE run_id = :id AND project_id = :p AND step_key = '' "
                        + "AND error_class IS NOT NULL "
                        + "GROUP BY k HAVING SUM((v)::bigint) > 0 "
                        + "ORDER BY class_count DESC, k ASC",
                new MapSqlParameterSource("id", runId).addValue("p", projectId))
                .stream()
                .map(row -> new PerfErrorClassCount(String.valueOf(row.get("error_class")),
                        ((Number) row.get("class_count")).longValue()))
                .toList();
    }

    @Override
    public int recordEvents(long projectId, long runId, List<PerfEventRow> events) {
        requireRunningRun(projectId, runId);
        if (events == null || events.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < events.size(); i++) {
            PerfEventRow e = events.get(i);
            int rowNo = i + 1;
            if (e.eventKind() == null || e.eventKind().isBlank()) {
                throw new ServiceException("第 " + rowNo + " 条事件类型为空，无法入事件流",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (e.secondOffset() < 0) {
                throw new ServiceException("第 " + rowNo + " 条事件秒偏移非法（"
                        + e.secondOffset() + "）", BizErrorCode.VALIDATION_ERROR);
            }
            if (e.detail() == null || e.detail().length() > MAX_EVENT_DETAIL_CHARS) {
                int len = e.detail() == null ? 0 : e.detail().length();
                throw new ServiceException("第 " + rowNo + " 条事件正文长度 " + len
                        + " 超过 " + MAX_EVENT_DETAIL_CHARS + " 字符，请执行器侧摘要后回传",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        List<MapSqlParameterSource> params = new ArrayList<>(events.size());
        for (PerfEventRow e : events) {
            // U-10 ingest-point masking: event detail is executor free text
            // (traceback, TLS notes) that can quote phones or e-mails; the
            // shared spectrum masks it record-time so the stored row — not
            // just the read-out — never keeps raw PII.
            params.add(new MapSqlParameterSource()
                    .addValue("run", runId).addValue("p", projectId)
                    .addValue("off", e.secondOffset())
                    .addValue("kind", e.eventKind())
                    .addValue("d", DesensitizeUtils.maskText(e.detail())));
        }
        int[] affected = jdbc.batchUpdate("INSERT INTO tp_perf_sample (run_id, project_id, "
                + "second_offset, event_kind, detail) VALUES (:run, :p, :off, :kind, :d)",
                params.toArray(MapSqlParameterSource[]::new));
        int inserted = 0;
        for (int n : affected) {
            inserted += Math.max(n, 0);
        }
        trimEventTail(runId, projectId, configReader.eventsTailCap());
        return inserted;
    }

    /**
     * R67 bounded event window at the write gate (R57 pair at the creation
     * point): right after each ingest batch the stored rows of one run are
     * trimmed to the newest `cap`; the overflow of oldest rows is deleted in
     * the same call, so drawer read-out and storage share one ceiling and a
     * long soak can never accumulate the event leg without bound. The trim
     * never throws the ingest off: a failed eviction leaves yesterday's
     * bounded-by-yesterday window intact, the next batch retries.
     */
    private void trimEventTail(long runId, long projectId, int cap) {
        MapSqlParameterSource scope = new MapSqlParameterSource("run", runId)
                .addValue("p", projectId);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM tp_perf_sample "
                + "WHERE run_id = :run AND project_id = :p", scope, Integer.class);
        if (total == null || total <= cap) {
            return;
        }
        jdbc.update("DELETE FROM tp_perf_sample WHERE id IN (SELECT id FROM tp_perf_sample "
                + "WHERE run_id = :run AND project_id = :p ORDER BY id ASC LIMIT :excess)",
                scope.addValue("excess", total - cap));
    }

    @Override
    public List<Map<String, Object>> listEvents(long projectId, long runId) {
        return jdbc.queryForList("SELECT " + EVENT_SELECT + " FROM tp_perf_sample "
                + "WHERE run_id = :id AND project_id = :p ORDER BY id",
                new MapSqlParameterSource("id", runId).addValue("p", projectId));
    }


    
    private void requireRunningRun(long projectId, long runId) {
        Integer status = jdbc.queryForList("SELECT status FROM tp_perf_run "
                        + "WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", runId).addValue("p", projectId))
                .stream().findFirst().map(r -> ((Number) r.get("status")).intValue())
                .orElseThrow(() -> new ServiceException(
                        "压测运行不存在或不属于该项目，采集数据无法入库", BizErrorCode.NOT_FOUND));
        if (status != 1) {
            throw new ServiceException("该压测运行已结束，采集数据拒收（停压后迟到帧不改写曲线口径）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
