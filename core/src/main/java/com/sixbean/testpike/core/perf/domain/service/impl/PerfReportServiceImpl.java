package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.perf.domain.component.PerfAttribution;
import com.sixbean.testpike.core.perf.domain.component.PerfCeilingFinding;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.component.PerfHistogram;
import com.sixbean.testpike.core.perf.domain.component.PerfHostStrainBoundary;
import com.sixbean.testpike.core.perf.domain.component.PerfLadderCeilingAnalyzer;
import com.sixbean.testpike.core.perf.domain.component.PerfMetricFrame;
import com.sixbean.testpike.core.perf.domain.component.PerfResidualGuide;
import com.sixbean.testpike.core.perf.domain.component.PerfSlaJudge;
import com.sixbean.testpike.core.perf.domain.service.PerfMetricService;
import com.sixbean.testpike.core.perf.domain.service.PerfReportService;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.common.domain.component.PdfHtmlRenderer;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;


@Service
public class PerfReportServiceImpl implements PerfReportService {

    private final PerfRunService perfRunService;
    private final PerfMetricService perfMetricService;
    private final PerfScenarioService perfScenarioService;
    private final PerfSlaJudge slaJudge;
    private final PerfLadderCeilingAnalyzer ladderCeilingAnalyzer;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfConfigReader perfConfigReader;
    private final PerfHostStrainBoundary hostStrainBoundary;
    private final PerfResidualGuide residualGuide;

    public PerfReportServiceImpl(PerfRunService perfRunService, PerfMetricService perfMetricService,
                                 PerfScenarioService perfScenarioService,
                                 PerfSlaJudge slaJudge,
                                 PerfLadderCeilingAnalyzer ladderCeilingAnalyzer,
                                 NamedParameterJdbcTemplate jdbc,
                                 ObjectMapper objectMapper,
                                 PerfConfigReader perfConfigReader,
                                 PerfHostStrainBoundary hostStrainBoundary,
                                 PerfResidualGuide residualGuide) {
        this.perfRunService = perfRunService;
        this.perfMetricService = perfMetricService;
        this.perfScenarioService = perfScenarioService;
        this.slaJudge = slaJudge;
        this.ladderCeilingAnalyzer = ladderCeilingAnalyzer;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.perfConfigReader = perfConfigReader;
        this.hostStrainBoundary = hostStrainBoundary;
        this.residualGuide = residualGuide;
    }

    @Override
    public Map<String, Object> build(long projectId, long runId) {
        Map<String, Object> run = perfRunService.view(projectId, runId);
        //  stage-b: aggregate caliber read (step_key='') — per-step frames share
        // seconds with the aggregate frame and must not inflate the curve, the metric
        // card, or the weighted recomputation (B-4 step lines land in a dedicated view).
        List<Map<String, Object>> rows = perfMetricService.listAggregateFrames(projectId, runId);
        List<PerfMetricFrame> frames = toFrames(rows);

        Map<String, Object> report = new HashMap<>();
        report.put("run", run);
        report.put("frames", rows);
        report.put("card", metricCard(frames));
        report.put("verdict", verdictRow(run, frames));
        report.put("caliberFootnote", caliberFootnote(frames, engineOf(run, projectId)));
        // CF-1 : a LADDER run carries the ceiling-search finding (tiers,
        // first breaching step, SLO-safe watermark, knee annotation) next to the
        // whole-run verdict; other pressure models honestly stay absent.
        report.put("ceiling", ceilingRow(run, frames));
        Integer driverLimit = driverLimitOf(run, projectId);
        report.put("driverLimit", driverLimit);
        report.put("boundaryNote", boundaryNote(driverLimit, frames));
        // B-10 : the TLS tier this run pinned at launch (strict /
        // custom_ca / insecure) rides the report face so readers can prove which
        // verification tier produced the numbers, and the frontend keeps its
        // honest yellow hint on a single-source key. Legacy snapshots predating
        // the pinned key stay null — the honest absence, never a fabricated tier
        // (same read posture as dslHashOf above).
        report.put("tlsTier", snapshotTlsTier(run.get("pressure_snapshot")));
        // GB-4 : residual-cleanup guide keyed off the isolation mode pinned
        // at launch — each declared mode (SHADOW_TABLE / NS_ACCOUNT / HEADER_MARK)
        // gets its cleanup instruction sheet on the report face, NS_ACCOUNT draws
        // the account recycle list from the pinned dataset's ledger row. Undeclared
        // or unknown spellings stay null (honest absence, same read posture as the
        // TLS tier above — the guide never scans the SUT data plane itself).
        report.put("residualGuide", residualGuideRow(run));
        return report;
    }

    @Override
    public Map<String, Object> compare(long projectId, long runA, long runB) {
        Map<String, Object> viewA = perfRunService.view(projectId, runA);
        Map<String, Object> viewB = perfRunService.view(projectId, runB);

        // Comparability basis : same scenario OR identical
        // pinned DSL fingerprint. Cloned scenarios pressing a byte-identical DSL
        // become comparable; a legacy run without a fingerprint never gains the
        // cross-scenario path (missing hash is never guessed equal — fail back to
        // the strict same-scenario gate). Rejection stays in save-time human
        // wording naming both bases (the historical no-cross-scenario misreading
        // discipline stays, only its refusal wording gains the second basis).
        Object scenarioA = viewA.get("scenario_id");
        Object scenarioB = viewB.get("scenario_id");
        boolean sameScenario = scenarioA != null
                && String.valueOf(scenarioA).equals(String.valueOf(scenarioB));
        String dslHashA = dslHashOf(viewA);
        String dslHashB = dslHashOf(viewB);
        boolean sameDsl = dslHashA != null && dslHashA.equals(dslHashB);
        if (!sameScenario && !sameDsl) {
            throw new ServiceException("两次运行非同一场景且压测脚本定义不同，曲线不可对比——"
                    + "请选择同一场景下的两个运行，或确认两场景的步骤/压力/阈值完全一致",
                    BizErrorCode.VALIDATION_ERROR);
        }

        Map<Integer, Map<String, Object>> framesA = indexBySecondOffset(runA, projectId);
        Map<Integer, Map<String, Object>> framesB = indexBySecondOffset(runB, projectId);

        TreeSet<Integer> offsets = new TreeSet<>();
        offsets.addAll(framesA.keySet());
        offsets.addAll(framesB.keySet());
        List<Map<String, Object>> series = new ArrayList<>(offsets.size());
        List<Map<String, Object>> diffs = new ArrayList<>();
        for (Integer offset : offsets) {
            Map<String, Object> a = framesA.get(offset);
            Map<String, Object> b = framesB.get(offset);
            Map<String, Object> point = new HashMap<>();
            point.put("secondOffset", offset);
            // Overlay series ships the RPS scalar per side (the chart note and the
            // frontend PerfCompareView contract both say "seconds -> RPS"); the
            // whole frame row would land as NaN inside Math.min/scaleY. A missing
            // second stays null — the line simply breaks there, never interpolated.
            point.put("a", a == null ? null : a.get("rps"));
            point.put("b", b == null ? null : b.get("rps"));
            series.add(point);
            if (a != null && b != null) {
                diffs.add(deltaRow(offset, a, b));
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("scenarioId", scenarioA);
        result.put("runA", viewA);
        result.put("runB", viewB);
        result.put("series", series);
        result.put("diffs", diffs);
        return result;
    }

    /**
     * Trend read face : ownership check rides the scenario view
     * single point (foreign/unknown = NOT_FOUND human wording, no existence
     * leak), then terminal runs only, newest-window first truncation through the
     * hot-updatable K8 key, rows returned ascending by run id. Per-run statistics
     * ride the exact judge caliber family — merged buckets at nearest rank for
     * p50/p95/p99 (no buckets = per-second p95 max fallback with p50/p99 null,
     * never fabricated), weighted error rate when bucket counts exist else the
     * equal-weight per-second mean — so the trend chart and the SLA verdict of
     * the same run can never disagree.
     */
    @Override
    public List<Map<String, Object>> trend(long projectId, long scenarioId) {
        perfScenarioService.view(projectId, scenarioId);
        List<Map<String, Object>> all = perfRunService.listByScenario(projectId, scenarioId);
        int window = perfConfigReader.trendMaxRuns();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> run : all) {
            int status = ((Number) run.get("status")).intValue();
            if (status != 2 && status != 3 && status != 4) {
                continue;
            }
            if (rows.size() >= window) {
                break;
            }
            rows.add(trendRow(projectId, run));
        }
        Collections.reverse(rows);
        return rows;
    }

    /** One trend row: run identity/mark fields + recomputed whole-run statistics. */
    private Map<String, Object> trendRow(long projectId, Map<String, Object> run) {
        long runId = ((Number) run.get("id")).longValue();
        List<PerfMetricFrame> frames = toFrames(
                perfMetricService.listAggregateFrames(projectId, runId));

        long[] merged = PerfHistogram.merge(frames);
        boolean histUsable = PerfHistogram.hasSamples(merged);
        boolean bucketCaliber = frames.stream().anyMatch(f -> f.reqCount() != null);
        long totalReq = frames.stream().mapToLong(f -> f.reqCount() == null ? 0L : f.reqCount()).sum();
        long totalFail = frames.stream().mapToLong(f -> f.failCount() == null ? 0L : f.failCount()).sum();

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("runId", runId);
        row.put("status", run.get("status"));
        row.put("mark", run.get("mark"));
        row.put("startedAt", run.get("started_at"));
        row.put("finishedAt", run.get("finished_at"));
        row.put("dslHash", dslHashOf(run));
        row.put("totalReq", totalReq);
        row.put("sampleSeconds", frames.stream()
                .map(PerfMetricFrame::secondOffset).distinct().count());
        if (histUsable) {
            row.put("p50Ms", PerfHistogram.quantile(merged, 0.50));
            row.put("p95Ms", PerfHistogram.quantile(merged, 0.95));
            row.put("p99Ms", PerfHistogram.quantile(merged, 0.99));
            row.put("quantileCaliber", "BUCKET");
        } else {
            // Fallback caliber (legacy executor frames carry no buckets): the honest
            // worst-second reading stays, p50/p99 remain null instead of invented.
            row.put("p50Ms", null);
            row.put("p95Ms", frames.stream().mapToDouble(PerfMetricFrame::p95Ms).max().orElse(0));
            row.put("p99Ms", null);
            row.put("quantileCaliber", "FALLBACK");
        }
        if (bucketCaliber && totalReq > 0) {
            row.put("errorRate", (double) totalFail / totalReq);
            row.put("errorCaliber", "WEIGHTED");
        } else {
            row.put("errorRate", frames.stream()
                    .mapToDouble(PerfMetricFrame::errorRate).average().orElse(0));
            row.put("errorCaliber", "MEAN");
        }
        return row;
    }

    /**
     * Read the pinned DSL fingerprint out of a run view's snapshot JSON.
     * Missing snapshot / corrupt snapshot / no dslHash key (legacy rows predating
     * the fingerprint feature) all map to null — null never participates in an
     * equality, which keeps the historical strict behavior for legacy runs.
     */
    private String dslHashOf(Map<String, Object> runView) {
        Object rawSnapshot = runView.get("pressure_snapshot");
        if (rawSnapshot == null) {
            return null;
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(String.valueOf(rawSnapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object hash = snapshot.get("dslHash");
            return hash == null ? null : String.valueOf(hash);
        } catch (Exception corrupt) {
            return null;
        }
    }

    /** Stage-b index: aggregate caliber only — same-second step frames would
     *  otherwise overwrite the aggregate row in this index. */
    private Map<Integer, Map<String, Object>> indexBySecondOffset(long runId, long projectId) {
        Map<Integer, Map<String, Object>> indexed = new HashMap<>();
        for (Map<String, Object> row : perfMetricService.listAggregateFrames(projectId, runId)) {
            Object offset = row.get("second_offset");
            if (offset instanceof Number n) {
                indexed.put(n.intValue(), row);
            }
        }
        return indexed;
    }

    
    private Map<String, Object> deltaRow(int offset, Map<String, Object> a, Map<String, Object> b) {
        Map<String, Object> row = new HashMap<>();
        row.put("secondOffset", offset);
        row.put("rpsDelta", num(a, "rps") - num(b, "rps"));
        row.put("p95DeltaMs", num(a, "p95_ms") - num(b, "p95_ms"));
        row.put("errorRateDelta", num(a, "error_rate") - num(b, "error_rate"));
        return row;
    }

    private static double num(Map<String, Object> row, String key) {
        return row.get(key) instanceof Number n ? n.doubleValue() : 0.0;
    }


    
    private Map<String, Object> metricCard(List<PerfMetricFrame> frames) {
        Map<String, Object> card = new HashMap<>();
        card.put("sampledSeconds", frames.size());
        card.put("avgRps", frames.stream().mapToDouble(PerfMetricFrame::rps).average().orElse(0));
        card.put("maxRps", frames.stream().mapToDouble(PerfMetricFrame::rps).max().orElse(0));
        card.put("peakSecondP95Ms",
                frames.stream().mapToDouble(PerfMetricFrame::p95Ms).max().orElse(0));
        card.put("avgErrorRate",
                frames.stream().mapToDouble(PerfMetricFrame::errorRate).average().orElse(0));
        List<PerfMetricFrame> auth = frames.stream()
                .filter(f -> f.authMs() != null || f.authCount() != null).toList();
        card.put("authSeconds", auth.size());
        card.put("authRequestCount",
                auth.stream().mapToDouble(f -> f.authCount() == null ? 0 : f.authCount()).sum());
        card.put("authAvgMs", auth.isEmpty() ? null
                : auth.stream().mapToDouble(PerfMetricFrame::authMs).average().orElse(0));
        return card;
    }

    /**
     * CI gate verdict read face: one run's pass/fail/pending answer for a CI
     * pipeline, delegating the actual judgment to the same verdictRow assembly
     * the report uses (no second judgment head — red line: reuse over copy).
     * Pending shapes come first: a VOID-marked run is never a gate basis (the
     * void is named as the reason), a non-terminal run is never hard-judged on
     * partial data (the current status is named instead).
     */
    @Override
    public Map<String, Object> ciVerdict(long projectId, long runId) {
        // Ownership rides the run view single point (foreign/unknown = NOT_FOUND
        // human wording, no existence leak — same discipline as build/compare).
        Map<String, Object> run = perfRunService.view(projectId, runId);
        int status = ((Number) run.get("status")).intValue();
        Object rawMark = run.get("mark");
        String mark = rawMark == null ? null : String.valueOf(rawMark);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", runId);
        out.put("mark", mark);
        out.put("status", status);
        if ("VOID".equals(mark)) {
            out.put("verdict", "pending");
            out.put("verdictPhrase", "该运行已标记作废（VOID），作废运行不作门禁依据——"
                    + "请以未作废的终态运行取结论");
            out.put("breachedGates", List.of());
            return out;
        }
        if (status != 2 && status != 3 && status != 4) {
            out.put("verdict", "pending");
            out.put("verdictPhrase", "压测尚未结束（当前状态：运行中），"
                    + "请等待运行进入终态（正常结束/熔断停/人工停）后再取判定结论");
            out.put("breachedGates", List.of());
            return out;
        }
        List<PerfMetricFrame> frames = toFrames(
                perfMetricService.listAggregateFrames(projectId, runId));
        Map<String, Object> judged = verdictRow(run, frames);
        boolean passed = Boolean.TRUE.equals(judged.get("passed"));
        out.put("verdict", passed ? "pass" : "fail");
        out.put("verdictPhrase", judged.get("conclusion"));
        out.put("breachedGates", judged.get("breachedGates"));
        return out;
    }


    private Map<String, Object> verdictRow(Map<String, Object> run, List<PerfMetricFrame> frames) {
        Map<String, Object> judgeInput = new HashMap<>(run);
        judgeInput.put("sla", snapshotSla(run.get("pressure_snapshot")));
        PerfAttribution attribution = slaJudge.judge(judgeInput, frames);
        Map<String, Object> verdict = new HashMap<>();
        verdict.put("passed", attribution.passed());
        verdict.put("conclusion", attribution.conclusion());
        verdict.put("breachedGates", attribution.breachedGates());
        return verdict;
    }

    
    private Map<String, Object> snapshotSla(Object rawSnapshot) {
        if (rawSnapshot == null) {
            return Map.of();
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(String.valueOf(rawSnapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object sla = snapshot.get("sla");
            return sla instanceof Map ? castMap(sla) : Map.of();
        } catch (Exception corrupt) {
            return Map.of();
        }
    }

    /**
     * B-10 TLS tier read from the pinned snapshot : same read
     * posture as dslHashOf — missing snapshot / corrupt snapshot / no tlsMode
     * key (legacy rows predating the tier pin) all map to null. Null renders
     * the honest "not recorded" wording; the report never fabricates "strict"
     * for a run whose tier was never pinned.
     */
    private String snapshotTlsTier(Object rawSnapshot) {
        return snapshotString(rawSnapshot, "tlsMode");
    }

    /**
     * GB-4 read face: the guide consumes ONLY launch-pinned snapshot values (mode +
     * datasetId) — the ledger row is looked up by the pinned id inside the run's own
     * project, and a vanished ledger row degrades inside the guide (honest wording),
     * never a re-read of the live scenario (batch discipline: no source-table
     * back-query in-run). A corrupt snapshot yields null like the TLS tier above.
     */
    private Map<String, Object> residualGuideRow(Map<String, Object> run) {
        Object rawSnapshot = run.get("pressure_snapshot");
        if (rawSnapshot == null) {
            return null;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = objectMapper.readValue(String.valueOf(rawSnapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception corrupt) {
            return null;
        }
        Object mode = snapshot.get("isolationMode");
        String isolationMode = mode == null || String.valueOf(mode).isBlank()
                ? null : String.valueOf(mode);
        Map<String, Object> datasetRow = null;
        if ("NS_ACCOUNT".equals(isolationMode) && snapshot.get("datasetId") != null) {
            long datasetId = ((Number) snapshot.get("datasetId")).longValue();
            datasetRow = jdbc.queryForList("SELECT name, columns_json::text AS columns_json, row_count "
                            + "FROM tp_perf_dataset WHERE id = :d AND project_id = :p",
                    new MapSqlParameterSource("d", datasetId)
                            .addValue("p", ((Number) run.get("project_id")).longValue()))
                    .stream().findFirst().orElse(null);
        }
        return residualGuide.build(isolationMode, datasetRow);
    }

    // ---------- CF-1 ceiling-search row (LADDER step grouping via PerfLadderCeilingAnalyzer) ----------

    /**
     * Ceiling-search row of the report: only LADDER runs (the staircase model) get one.
     * Other pressure models honestly return null (no tiers to walk — never a fabricated
     * ceiling). Thresholds ride the pinned snapshot's sla, same single source as the
     * whole-run verdict.
     */
    private Map<String, Object> ceilingRow(Map<String, Object> run, List<PerfMetricFrame> frames) {
        Object rawSnapshot = run.get("pressure_snapshot");
        if (rawSnapshot == null) {
            return null;
        }
        Map<String, Object> snapshot;
        try {
            snapshot = objectMapper.readValue(String.valueOf(rawSnapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception corrupt) {
            return null;
        }
        if (!"LADDER".equals(String.valueOf(snapshot.get("pressureModel")))) {
            return null;
        }
        Map<String, Object> pressureConfig = snapshot.get("pressureConfig") instanceof String raw
                ? parseJsonMap(raw)
                : asMapOrNull(snapshot.get("pressureConfig"));
        Map<String, Object> judgeInput = new HashMap<>(run);
        judgeInput.put("sla", snapshot.get("sla") instanceof Map<?, ?> ? castMap(snapshot.get("sla")) : Map.of());
        PerfCeilingFinding finding = ladderCeilingAnalyzer.analyze(judgeInput,
                pressureConfig == null ? Map.of() : pressureConfig, frames);
        Map<String, Object> row = new HashMap<>();
        row.put("applicable", finding.applicable());
        row.put("tiers", finding.tiers());
        row.put("breachStep", finding.breachStep());
        row.put("lastPassStep", finding.lastPassStep());
        row.put("safeWatermarkRps", finding.safeWatermarkRps());
        row.put("kneeStep", finding.kneeStep());
        row.put("kneeDescription", finding.kneeDescription());
        row.put("conclusion", finding.conclusion());
        row.put("caliberNote", finding.caliberNote());
        return row;
    }

    /** Pressure-config JSON string -> map (corrupt = null, analyzer degrades honestly). */
    private Map<String, Object> parseJsonMap(String raw) {
        try {
            return objectMapper.readValue(raw,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
        } catch (Exception corrupt) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMapOrNull(Object raw) {
        return raw instanceof Map ? (Map<String, Object>) raw : null;
    }


    private String caliberFootnote(List<PerfMetricFrame> frames, String engine) {
        // D3 disclosure: the engine-side rate caliber rides this same footnote
        // family, wording single-sourced from PerfEngineDisclosure (no second
        // paraphrase). Exact-schedule engines yield null = honest absence.
        String caveat = com.sixbean.testpike.core.perf.domain.component
                .PerfEngineDisclosure.caveatFor(engine);
        return "口径注脚：采样窗口 " + frames.size() + " 秒（run 全程秒帧，停压后迟到帧不入）；"
                + "统计方法=每秒独立归帧——P95 为各秒最近秩 ceil(0.95n)（归一算法在执行器一处定义），"
                + "报告展示「峰值秒 P95」=各秒 P95 最大值，吞吐/错误率为各秒均值，"
                + "认证耗时单列不进三线（D5 分桶）；"
                + "预热期剔除规则=当前未启用剔除（全秒帧入统计，含爬坡段），"
                + "如引擎申报预热窗口将在注脚如实标注。"
                + (caveat == null ? "" : "引擎口径披露：" + caveat);
    }

    /** Engine read for the disclosure face: the launch-pinned snapshot engine
     *  wins (same door the dispatch resolved — report truth equals press truth);
     *  runs pinned before the engine key existed fall back to the scenario join
     *  honestly (legacy shape, no fabricated value). Display-only read, this door
     *  never writes. */
    private String engineOf(Map<String, Object> run, long projectId) {
        String pinned = snapshotString(run.get("pressure_snapshot"), "engine");
        if (pinned != null) {
            return pinned;
        }
        Object scenarioId = run.get("scenario_id");
        if (scenarioId == null) {
            return null;
        }
        return jdbc.queryForList("SELECT engine FROM tp_perf_scenario "
                        + "WHERE id = :s AND project_id = :p",
                new MapSqlParameterSource("s", scenarioId).addValue("p", projectId))
                .stream().findFirst()
                .map(r -> r.get("engine") == null ? null : String.valueOf(r.get("engine")))
                .orElse(null);
    }

    /** Single-key string read of a pinned JSONB snapshot (absent/blank/corrupt
     *  = null). Snapshot faces consume this door instead of opening another
     *  per-key parse copy (R40 — the tls-tier face was the last copy and now
     *  rides through here too). */
    private String snapshotString(Object rawSnapshot, String key) {
        if (rawSnapshot == null) {
            return null;
        }
        try {
            Map<String, Object> snapshot = objectMapper.readValue(String.valueOf(rawSnapshot),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                    });
            Object value = snapshot.get(key);
            return value == null || String.valueOf(value).isBlank() ? null : String.valueOf(value);
        } catch (Exception corrupt) {
            return null;
        }
    }


    
    private Integer driverLimitOf(Map<String, Object> run, long projectId) {
        Object scenarioId = run.get("scenario_id");
        if (scenarioId == null) {
            return null;
        }
        return jdbc.queryForList("SELECT driver_limit FROM tp_perf_scenario "
                        + "WHERE id = :s AND project_id = :p",
                new MapSqlParameterSource("s", scenarioId).addValue("p", projectId))
                .stream().findFirst()
                .map(r -> r.get("driver_limit") == null ? null
                        : ((Number) r.get("driver_limit")).intValue())
                .orElse(null);
    }

    private String boundaryNote(Integer driverLimit, List<PerfMetricFrame> frames) {
        String base = driverLimit == null
                ? "驱动上限未披露——本报告红绿灯仅对被测上限（阈值门禁三门禁）给出结论，"
                        + "引擎侧驱动能力分界未标注"
                : "驱动上限披露 " + driverLimit
                        + "——超出该值属引擎侧能力边界（驱动上限），与被测上限（阈值门禁）分界标注，"
                        + "红绿灯结论只判被测上限";
        // B-5 self-monitor: when the press box was saturated, a flattening curve is
        // honestly attributable to the engine host, not the target — the strain
        // segment rides the same note (no second footnote family). Frames without
        // samples (legacy executor) yield no segment: never a fabricated reading.
        String strain = hostStrainBoundary.assess(frames,
                perfConfigReader.selfMonitorLoadRatioCeiling());
        return strain == null ? base : base + "；" + strain;
    }


    /** Persisted rows -> frame contract (nullable families stay NULL, never 0-substituted;
     *  bucket columns mapped too so whole-run recomputation consumes them). */
    private List<PerfMetricFrame> toFrames(List<Map<String, Object>> rows) {
        List<PerfMetricFrame> frames = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            frames.add(new PerfMetricFrame(
                    ((Number) row.get("second_offset")).intValue(),
                    ((Number) row.get("rps")).doubleValue(),
                    ((Number) row.get("p95_ms")).doubleValue(),
                    ((Number) row.get("error_rate")).doubleValue(),
                    nullableDouble(row.get("ttft_ms")), nullableDouble(row.get("itl_ms")),
                    nullableDouble(row.get("tokens_per_sec")),
                    nullableDouble(row.get("auth_ms")), nullableDouble(row.get("auth_count")),
                    nullableLong(row.get("req_count")), nullableLong(row.get("fail_count")),
                    nullableDouble(row.get("duration_sum_ms")),
                    textOrNull(row.get("hist")), textOrNull(row.get("error_class")),
                    row.get("step_key") == null ? "" : String.valueOf(row.get("step_key")),
                    textOrNull(row.get("host_stats"))));
        }
        return frames;
    }

    private static Double nullableDouble(Object raw) {
        return raw instanceof Number n ? n.doubleValue() : null;
    }

    /** Bucket fields mapped too, so the report/judge path consumes whole-run raw material. */
    private static Long nullableLong(Object raw) {
        return raw instanceof Number n ? n.longValue() : null;
    }

    /** Textualize a jsonb column read-back (PGobject.toString() yields the original text;
     *  missing/NULL keeps the frame contract's null semantics). */
    private static String textOrNull(Object raw) {
        return raw == null ? null : String.valueOf(raw);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object raw) {
        return (Map<String, Object>) raw;
    }

    // ---------- B-12 run-report export ----------

    /** Accepted export formats (pdf rides the shared PdfHtmlRenderer; html is
     *  the same document without the render step — one assembly, two carriers). */
    private static final java.util.Set<String> EXPORT_FORMATS = java.util.Set.of("pdf", "html");

    @Override
    public byte[] export(long projectId, long runId, String format) {
        String normalized = format == null ? "" : format.trim().toLowerCase();
        if (!EXPORT_FORMATS.contains(normalized)) {
            throw new ServiceException("导出格式不支持（仅支持 pdf/html）", BizErrorCode.VALIDATION_ERROR);
        }
        // Single source: the exported document serializes exactly what build()
        // assembles for the online page — never a second computation head.
        String html = buildExportHtml(projectId, runId);
        if ("html".equals(normalized)) {
            return html.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        try {
            return PdfHtmlRenderer.render(html);
        } catch (Exception e) {
            throw new ServiceException("PDF 导出失败", BizErrorCode.INTERNAL_ERROR, e);
        }
    }

    /** Status code → human label (same enumeration the run detail header shows). */
    private static String statusLabel(Object status) {
        int code = status instanceof Number n ? n.intValue() : -1;
        return switch (code) {
            case 1 -> "运行中";
            case 2 -> "正常结束";
            case 3 -> "熔断停止";
            case 4 -> "人工停止";
            default -> "未知";
        };
    }

    /** Assemble the export document from the build() face (identity line + metric
     *  card + verdict + ceiling finding when present + caliber footnote). All
     *  values pass through HTML escaping — the caller of PdfHtmlRenderer owns
     *  escaping (same posture as the J3/O10 export family). */
    private String buildExportHtml(long projectId, long runId) {
        Map<String, Object> report = build(projectId, runId);
        Map<String, Object> run = castMap(report.get("run"));
        Map<String, Object> card = castMap(report.get("card"));
        Map<String, Object> verdict = castMap(report.get("verdict"));
        String scenarioName = "";
        Object scenarioId = run.get("scenario_id");
        if (scenarioId instanceof Number s) {
            Object name = perfScenarioService.view(projectId, s.longValue()).get("name");
            scenarioName = name == null ? "" : String.valueOf(name);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<html><head><meta charset=\"UTF-8\"/><style>")
                .append("body { font-family: \"Noto Sans SC\", sans-serif; font-size: 12px; color: #1f2d3d; margin: 24px; }")
                .append("h1 { font-size: 20px; text-align: center; }")
                .append("h2 { font-size: 14px; border-left: 4px solid #3370ff; padding-left: 8px; margin-top: 20px; }")
                .append("table { border-collapse: collapse; width: 100%; margin-top: 8px; }")
                .append("th, td { border: 1px solid #c0c4cc; padding: 6px 10px; text-align: left; }")
                .append("th { background: #eef2ff; }")
                .append(".meta { color: #606266; font-size: 11px; text-align: center; }")
                .append(".footnote { background: #f5f7fa; padding: 10px; border-radius: 4px; font-size: 11px; }")
                .append("</style></head><body>");
        sb.append("<h1>压测运行报告 #").append(runId).append("</h1>");
        sb.append("<p class=\"meta\">场景：").append(esc(scenarioName))
                .append(" ｜ 状态：").append(esc(statusLabel(run.get("status"))))
                .append(" ｜ 标记：").append(esc(run.get("mark") == null ? "—" : String.valueOf(run.get("mark"))))
                .append(" ｜ 开始：").append(esc(String.valueOf(run.get("started_at"))))
                .append(" ｜ 结束：").append(esc(run.get("finished_at") == null ? "—" : String.valueOf(run.get("finished_at"))))
                .append("</p>");
        sb.append("<h2>一、指标卡</h2><table>")
                .append("<tr><th>采样窗口(秒)</th><th>平均RPS</th><th>峰值RPS</th><th>峰值秒P95(ms)</th><th>平均错误率</th><th>认证请求数</th><th>认证均耗时(ms)</th></tr>")
                .append("<tr><td>").append(num(card.get("sampledSeconds")))
                .append("</td><td>").append(num(card.get("avgRps")))
                .append("</td><td>").append(num(card.get("maxRps")))
                .append("</td><td>").append(num(card.get("peakSecondP95Ms")))
                .append("</td><td>").append(num(card.get("avgErrorRate")))
                .append("</td><td>").append(num(card.get("authRequestCount")))
                .append("</td><td>").append(num(card.get("authAvgMs")))
                .append("</td></tr></table>");
        sb.append("<h2>二、红绿灯结论</h2><p>")
                .append(Boolean.TRUE.equals(verdict.get("passed")) ? "通过" : "未通过")
                .append("：").append(esc(String.valueOf(verdict.get("conclusion"))));
        Object breached = verdict.get("breachedGates");
        if (breached instanceof List<?> list && !list.isEmpty()) {
            sb.append("（越线门禁：");
            List<String> names = new ArrayList<>();
            for (Object g : list) {
                names.add(esc(String.valueOf(g)));
            }
            sb.append(String.join("、", names)).append("）");
        }
        sb.append("</p>");
        Object ceiling = report.get("ceiling");
        if (ceiling instanceof Map<?, ?> ceil && !ceil.isEmpty()) {
            sb.append("<h2>三、寻顶结论（CF-1）</h2><p>")
                    .append(esc(String.valueOf(ceil))).append("</p>");
        }
        sb.append("<h2>口径注脚</h2><p class=\"footnote\">")
                .append(esc(String.valueOf(report.get("caliberFootnote")))).append("</p>");
        sb.append("</body></html>");
        return sb.toString();
    }

    /** Metric value render: null stays an honest dash, everything else keeps one
     *  clean text form (no second formatting head). */
    private static String num(Object value) {
        return value == null ? "—" : String.valueOf(value);
    }

    private static String esc(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
