package com.sixbean.testpike.core.perf.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.stereotype.Component;

import java.util.Map;


@Component
public class PerfConfigReader {

    public static final String KEY_PRODUCTION_ENABLED = "perf.production-enabled";
    public static final String KEY_BLOCKED_SEGMENTS = "perf.blocked-target-segments";
    public static final String KEY_APPROVAL_VALID_HOURS = "perf.approval-valid-hours";
    public static final String KEY_PERF_LEASE_TTL = "lease.perf-ttl-seconds";
    public static final String KEY_BREAKER_ERROR_RATE = "perf.breaker-error-rate";
    
    public static final String KEY_DEFAULT_ENGINE = "perf.default-engine";
    public static final String KEY_BUDGET_MARGIN_MINUTES = "perf.budget-margin-minutes";
    /** CSV dataset row budget key (upload-time guard; hot-updatable, invalid falls back). */
    public static final String KEY_DATASET_MAX_ROWS = "perf.dataset.max-rows";
    /** CSV dataset preview line count key (read-back window; hot-updatable). */
    public static final String KEY_DATASET_PREVIEW_ROWS = "perf.dataset.preview-rows";
    /**
 * pool-wide default row-allocation strategy (K8 key; scenario-level
 * row_alloc_mode column wins when declared, this resolves the NULL case at
 * dispatch — hot-updatable, never hardcoded at call sites).
 */
    public static final String KEY_DATASET_ROW_ALLOC = "perf.dataset.row-alloc-mode";
    /** b2 partial-frame flush window (K8 key, hot-updatable): how often
 * perf_worker ships in-press seconds with heartbeats (03 §2.2 perfPartial). */
    public static final String KEY_FRAMES_FLUSH_INTERVAL = "perf.frames-flush-interval-seconds";
    /** b3 launch-time duration ceiling (K8 key, hot-updatable): a perf run
 * declared longer than this is rejected at launch — the precondition
 * guardrail of long-run batching (A-9 soak splits into ceiling-sized batches). */
    public static final String KEY_MAX_DURATION_HOURS = "perf.max-duration-hours";
    /** b3 terminal coverage tolerance (K8 key, hot-updatable): how many tail
 * seconds of a finished run may be un-stored before the curve is annotated
 * data-loss (covers the flush window still in flight when the engine exits). */
    public static final String KEY_COVERAGE_TOLERANCE_SECONDS = "perf.frames-coverage-tolerance-seconds";
    /** b5 live-curve point ceiling (K8 key, hot-updatable): seconds beyond this
 * many in one running-page poll response collapse into buckets server-side. */
    public static final String KEY_LIVE_CURVE_MAX_POINTS = "perf.live-curve.max-points";
    /** A-12 VU budget coefficient (K8 key, hot-updatable): virtual users to
 * pre-allocate per arrival rate unit in generated k6 scripts. Default 1 keeps
 * the historical 1:1 shape; the ceiling bounds a dirty value from exploding
 * memory on the press box. */
    public static final String KEY_VU_PER_RPS = "perf.vu-per-rps";
    /** CF-1 SLO-safe watermark ratio (K8 key, hot-updatable): the ceiling-search
 * report recommends last-passing-step nominal rate x this ratio as the operating
 * watermark. Default 0.8 = back off 20% from the proven-good load; the interval
 * (0,1] keeps a dirty value from inflating the watermark past the tested rate. */
    public static final String KEY_CEILING_SAFE_WATERMARK_RATIO = "perf.ceiling.safe-watermark-ratio";
    /** Scenario trend read window (K8, hot-updatable): how many newest terminal
     *  runs one scenario-trend read covers. Default 200 bounds a thousand-run
     *  scenario from exploding the read; below-1/dirty rows fall back to 200. */
    public static final String KEY_TREND_MAX_RUNS = "perf.trend.max-runs";
    /** scale contract (K8 key, hot-updatable): max http.request steps one
 * perf scenario may save. Default 500 = the worst-case fixture size the
 * wizard windowing is designed for; a dirty/out-of-range row falls back to
 * 500 so a mistyped value can never switch the ceiling off. */
    public static final String KEY_SCENARIO_MAX_STEPS = "perf.scenario.max-steps";
    /** D8 approval auto-launch switch (K8 key, hot-updatable): when true, approving a
     *  ticket that links a perf scenario fires the launch through the same single gate
     *  channel as manual/CI doors. Default false keeps today's behaviour — approving
     *  only unlocks the production window, launching stays a human action. */
    public static final String KEY_APPROVAL_AUTO_LAUNCH = "perf.approval.auto-launch.enabled";
    /** CF-6 canary pre-flight switch (K8 key, hot-updatable): when true, every
     *  launch first probes the effective environment at low load (reachability /
     *  TLS / baseline response time, plus a Gatling compile pre-warm) inside the
     *  launch transaction; a failed probe rejects with zero residue. Default
     *  false keeps today's launch behaviour byte-for-byte. */
    public static final String KEY_CANARY_ENABLED = "perf.canary.enabled";
    /** CF-6 probe count (K8 key, hot-updatable): sequential low-load requests per
     *  pre-flight. Default 5 — enough for a nearest-rank p95 without becoming a
     *  load itself; dirty/below-1 values fall back to 5. */
    public static final String KEY_CANARY_REQUESTS = "perf.canary.requests";
    /** CF-6 per-request timeout seconds (K8 key, hot-updatable; default 5). */
    public static final String KEY_CANARY_PROBE_TIMEOUT = "perf.canary.probe-timeout-seconds";
    /** CF-6 baseline ceiling in ms (K8 key, hot-updatable): measured p95 above this
     *  rejects the launch. Default 3000 = a target that cannot answer a single
     *  low-load GET in 3s is not worth pressing; dirty/non-positive falls back. */
    public static final String KEY_CANARY_BASELINE_P95_MS = "perf.canary.baseline-p95-ms";
    /** B-5 press-box strain ceiling (K8 key, hot-updatable): sampled loadRatio
     *  (1-minute load / cores) above this marks the report boundary note
     *  "driver-side bottleneck suspected". Default 0.85 = leave 15% headroom
     *  before the engine host itself saturates; dirty/non-positive falls back. */
    public static final String KEY_SELF_MONITOR_LOAD_RATIO = "perf.self-monitor.load-ratio";
    /** Run-history retention window in days (K8 key, hot-updatable): finished
     *  non-golden perf runs older than this are cleared by the daily retention run
     *  together with their metric/sample child rows. Golden-marked runs are exempt
     *  forever. &lt;1 / non-numeric / missing falls back to 90 — a dirty value can
     *  never shrink history to nothing nor switch the sweep off. */
    public static final String KEY_RUN_RETENTION_DAYS = "perf.run.retention-days";

    private static final Map<String, String> DEFAULTS = Map.ofEntries(
            Map.entry(KEY_PRODUCTION_ENABLED, "false"),
            Map.entry(KEY_BLOCKED_SEGMENTS, ""),
            Map.entry(KEY_APPROVAL_VALID_HOURS, "72"),
            Map.entry(KEY_PERF_LEASE_TTL, "1800"),
            Map.entry(KEY_BREAKER_ERROR_RATE, "0.05"),
            Map.entry(KEY_DEFAULT_ENGINE, "GATLING"),
            Map.entry(KEY_BUDGET_MARGIN_MINUTES, "10"),
            Map.entry(KEY_DATASET_MAX_ROWS, "50000"),
            Map.entry(KEY_DATASET_PREVIEW_ROWS, "20"),
            Map.entry(KEY_DATASET_ROW_ALLOC, "CYCLE"),
            Map.entry(KEY_FRAMES_FLUSH_INTERVAL, "10"),
            Map.entry(KEY_MAX_DURATION_HOURS, "4"),
            Map.entry(KEY_COVERAGE_TOLERANCE_SECONDS, "60"),
            Map.entry(KEY_LIVE_CURVE_MAX_POINTS, "600"),
            Map.entry(KEY_VU_PER_RPS, "1"),
            Map.entry(KEY_CEILING_SAFE_WATERMARK_RATIO, "0.8"),
            Map.entry(KEY_TREND_MAX_RUNS, "200"),
            Map.entry(KEY_SCENARIO_MAX_STEPS, "500"),
            Map.entry(KEY_APPROVAL_AUTO_LAUNCH, "false"),
            Map.entry(KEY_CANARY_ENABLED, "false"),
            Map.entry(KEY_CANARY_REQUESTS, "5"),
            Map.entry(KEY_CANARY_PROBE_TIMEOUT, "5"),
            Map.entry(KEY_CANARY_BASELINE_P95_MS, "3000"),
            Map.entry(KEY_SELF_MONITOR_LOAD_RATIO, "0.85"),
            Map.entry(KEY_RUN_RETENTION_DAYS, "90"));

    private final ConfigService configService;

    public PerfConfigReader(ConfigService configService) {
        this.configService = configService;
    }

    
    public String value(String key) {
        return configService.getValue(key, DEFAULTS.getOrDefault(key, ""));
    }

    
    public boolean productionEnabled() {
        return "true".equalsIgnoreCase(value(KEY_PRODUCTION_ENABLED).trim());
    }

    /** D8 approval auto-launch switch: only an explicit "true" row enables firing on
     *  approve — missing/dirty rows keep the human-action default (off). */
    public boolean approvalAutoLaunchEnabled() {
        return "true".equalsIgnoreCase(value(KEY_APPROVAL_AUTO_LAUNCH).trim());
    }

    
    public int approvalValidHours() {
        try {
            return Integer.parseInt(value(KEY_APPROVAL_VALID_HOURS).trim());
        } catch (NumberFormatException e) {
            return 72;
        }
    }

    
    public double breakerErrorRate() {
        try {
            return Double.parseDouble(value(KEY_BREAKER_ERROR_RATE).trim());
        } catch (NumberFormatException e) {
            return 0.05;
        }
    }

    
    public int perfLeaseTtlSeconds() {
        try {
            return Integer.parseInt(value(KEY_PERF_LEASE_TTL).trim());
        } catch (NumberFormatException e) {
            return 1800;
        }
    }

    
    public String defaultEngine() {
        String raw = value(KEY_DEFAULT_ENGINE).trim().toUpperCase(java.util.Locale.ROOT);
        return PerfRunDispatchSupport.ENGINE_VALUES.contains(raw) ? raw
                : DEFAULTS.get(KEY_DEFAULT_ENGINE);
    }

    /**
     * Perf run budget margin in minutes (K8 key, hot-updatable). 0 is legal (raw
     * duration without margin); negative / non-numeric / missing falls back to 10
     * so a dirty value can never swallow the declared run duration.
     */
    public int budgetMarginMinutes() {
        try {
            int parsed = Integer.parseInt(value(KEY_BUDGET_MARGIN_MINUTES).trim());
            return parsed < 0 ? 10 : parsed;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
 * CSV dataset row budget . Upload refuses any
 * file whose data rows exceed this; &lt;1 / non-numeric / missing falls
 * back to the built-in default so a dirty value can never accept files
 * without a ceiling.
 */
    public int datasetMaxRows() {
        return positiveInt(KEY_DATASET_MAX_ROWS);
    }

    /** Preview line count streamed back by the dataset view (K8, same fallback posture). */
    public int datasetPreviewRows() {
        return positiveInt(KEY_DATASET_PREVIEW_ROWS);
    }

    /**
 * Partial-frame flush window seconds : the
 * dispatch projection ships it to perf_worker as framesFlushSeconds so the
 * in-press curve cadence is operator-tunable without touching the executor.
 * &lt;1 / &gt;300 / non-numeric / missing falls back to 10 (a dirty value can
 * never flood the heartbeat channel nor freeze the live curve beyond 5 min).
 */
    public int framesFlushSeconds() {
        try {
            int parsed = Integer.parseInt(value(KEY_FRAMES_FLUSH_INTERVAL).trim());
            return parsed >= 1 && parsed <= 300 ? parsed : 10;
        } catch (NumberFormatException e) {
            return 10;
        }
    }

    /**
 * Launch-time duration ceiling in hours .
 * 0 / negative / non-numeric / missing falls back to 4 — a dirty value can
 * never switch the ceiling off (long-run batching guardrail stays enforced).
 */
    public int maxDurationHours() {
        try {
            int parsed = Integer.parseInt(value(KEY_MAX_DURATION_HOURS).trim());
            return parsed >= 1 ? parsed : 4;
        } catch (NumberFormatException e) {
            return 4;
        }
    }

    /**
 * Terminal coverage tolerance in seconds :
 * missing tail seconds up to this value are treated as the flush window still
 * in flight at engine exit, not data loss. Negative / non-numeric / missing
 * falls back to 60 — a dirty value can never widen tolerance so far that every
 * loss verdict is suppressed, nor tighten below 0 to spam annotations.
 */
    public int coverageToleranceSeconds() {
        try {
            int parsed = Integer.parseInt(value(KEY_COVERAGE_TOLERANCE_SECONDS).trim());
            return parsed >= 0 && parsed <= 600 ? parsed : 60;
        } catch (NumberFormatException e) {
            return 60;
        }
    }

    /**
 * Live-curve point ceiling : the running page
 * poll never receives more raw-or-bucketed points than this per response.
 * &lt;10 / &gt;10000 / non-numeric / missing falls back to 600 — a dirty value can
 * neither flood the 2s poll nor starve the curve below one screenful.
 */
    public int liveCurveMaxPoints() {
        try {
            int parsed = Integer.parseInt(value(KEY_LIVE_CURVE_MAX_POINTS).trim());
            return parsed >= 10 && parsed <= 10000 ? parsed : 600;
        } catch (NumberFormatException e) {
            return 600;
        }
    }

    /**
 * Scenario step ceiling : the save
 * chain rejects a scenario whose step list exceeds this count in plain words.
 * &lt;1 / non-numeric / missing falls back to 500 — a dirty value can never
 * switch the ceiling off (a 0 or negative cap would silently accept anything).
 */
    public int scenarioMaxSteps() {
        try {
            int parsed = Integer.parseInt(value(KEY_SCENARIO_MAX_STEPS).trim());
            return parsed >= 1 ? parsed : 500;
        } catch (NumberFormatException e) {
            return 500;
        }
    }

    /** Scenario trend read window (K8 key, hot-updatable): newest-N terminal runs
     *  one trend request covers; &lt;1 / non-numeric / missing falls back to 200. */
    public int trendMaxRuns() {
        return positiveInt(KEY_TREND_MAX_RUNS);
    }

    /** Run-history retention window days (K8 key, hot-updatable); the retention
     *  sweep consumes this so the ceiling lives in the perf config single source, not
     *  in a second fallback table. &lt;1 / non-numeric / missing falls back to 90. */
    public int runRetentionDays() {
        return positiveInt(KEY_RUN_RETENTION_DAYS);
    }

    /**
 * Effective default row-allocation strategy for scenarios that leave
 * row_alloc_mode NULL . Values are matched
 * case-insensitively against PerfRowAllocModes; a dirty/unknown value falls
 * back to CYCLE — the safest spelling (a run never stalls on an empty pool).
 */
    public String datasetRowAllocMode() {
        String raw = value(KEY_DATASET_ROW_ALLOC).trim().toUpperCase(java.util.Locale.ROOT);
        return PerfRowAllocModes.VALUES.contains(raw) ? raw : PerfRowAllocModes.CYCLE;
    }

    /**
 * VU-per-RPS budget coefficient : k6 arrival-rate
 * executors pre-allocate ceil(rate x this) virtual users. &lt;1 / &gt;10 /
 * non-numeric / missing falls back to 1 — a dirty value can neither under-provision
 * VUs (throttled traffic faking the declared rate) nor explode them (press-box OOM).
 */
    public int vuPerRps() {
        try {
            int parsed = Integer.parseInt(value(KEY_VU_PER_RPS).trim());
            return parsed >= 1 && parsed <= 10 ? parsed : 1;
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /**
 * CF-1 SLO-safe watermark ratio : the ceiling-search
 * report multiplies the last passing ladder step's nominal rate by this value.
 * Outside (0,1] / non-numeric / missing falls back to 0.8 — a dirty value can
 * never recommend an operating point at or above the load that just breached.
 */
    public double ceilingSafeWatermarkRatio() {
        try {
            double parsed = Double.parseDouble(value(KEY_CEILING_SAFE_WATERMARK_RATIO).trim());
            return parsed > 0 && parsed <= 1 ? parsed : 0.8;
        } catch (NumberFormatException e) {
            return 0.8;
        }
    }

    private int positiveInt(String key) {
        try {
            int parsed = Integer.parseInt(value(key).trim());
            return parsed >= 1 ? parsed : Integer.parseInt(DEFAULTS.get(key));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULTS.get(key));
        }
    }

    /** CF-6 canary pre-flight switch: only an explicit "true" row enables the
     *  probe — missing/dirty rows keep today's launch behaviour (off). */
    public boolean canaryEnabled() {
        return "true".equalsIgnoreCase(value(KEY_CANARY_ENABLED).trim());
    }

    /** CF-6 probe count (dirty/below-1 falls back to the built-in default 5). */
    public int canaryRequests() {
        return positiveInt(KEY_CANARY_REQUESTS);
    }

    /** CF-6 per-request timeout seconds (dirty/below-1 falls back to 5). */
    public int canaryProbeTimeoutSeconds() {
        return positiveInt(KEY_CANARY_PROBE_TIMEOUT);
    }

    /** CF-6 baseline p95 ceiling in ms (dirty/non-positive falls back to 3000). */
    public int canaryBaselineP95Ms() {
        return positiveInt(KEY_CANARY_BASELINE_P95_MS);
    }

    /** B-5 press-box strain ceiling, ratio caliber (K8 key, hot-updatable): the
     *  report boundary note compares sampled loadRatio against this. &lt;=0 /
     *  non-numeric / missing falls back to 0.85 — a dirty value can never switch
     *  the strain check off (0-threshold would flag every run) nor widen it past
     *  the meaningful range. */
    public double selfMonitorLoadRatioCeiling() {
        try {
            double parsed = Double.parseDouble(value(KEY_SELF_MONITOR_LOAD_RATIO).trim());
            return parsed > 0 ? parsed : 0.85;
        } catch (NumberFormatException e) {
            return 0.85;
        }
    }

    
    public java.util.List<String> blockedSegments() {
        String raw = value(KEY_BLOCKED_SEGMENTS);
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String seg : raw.split(",")) {
            String t = seg.trim();
            if (!t.isEmpty()) {
                out.add(t.startsWith(".") ? t : "." + t);
            }
        }
        return out;
    }
}
