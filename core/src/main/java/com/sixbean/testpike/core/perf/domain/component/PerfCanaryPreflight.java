package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.TlsPolicy;
import com.sixbean.testpike.core.project.domain.component.EnvTlsSupport;
import com.sixbean.testpike.core.project.domain.component.TlsAwareHttpSupport;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CF-6 canary pre-flight : a low-load probe of the effective
 * environment that runs inside the launch transaction BEFORE the run row is
 * committed. The gap this closes: a launch previously paid the whole approval
 * window and the operator's waiting time for a run that could only discover at
 * press time that the target is dead, unreachable or pathologically slow.
 * <p>Shape: N sequential TLS-aware GETs against the environment's recorded
 * base_url (per the environment profile's TLS decision — the same
 * {@link TlsAwareHttpSupport} single source the health face uses, never a
 * second probe stack). Reachability = at least one probe got an HTTP answer
 * (a 404 still proves the target host lives; route correctness is not this
 * gate's job). Baseline = nearest-rank p95 of the answered latencies, rejected
 * when it exceeds the hot-updatable ceiling. For the GATLING default engine the
 * step additionally pre-warms the javac stack by compiling a throwaway
 * one-step simulation through the real adapter (GB-5: cold-start compensation
 * so the dispatch compile no longer pays first-run JIT/class-load latency).
 * <p>Posture: the whole feature is behind the perf.canary.enabled K8 switch,
 * default OFF — a disabled or dirty switch leaves today's launch behaviour
 * byte-for-byte (the probe returns null and the snapshot carries no canary
 * key). A failure throws a human-worded rejection inside the launch
 * transaction, so rejection = zero run rows and zero dispatch tasks by
 * construction. The passing outcome is returned for pinning into the run
 * snapshot ({@code canary}) so the report face can attribute against the
 * measured baseline.
 * <p>Thresholds are all K8 keys (hot-updatable, dirty values fall back to the
 * built-in defaults — never silently switch the pre-flight off).
 */
@Component
public class PerfCanaryPreflight {

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfConfigReader perfConfigReader;
    private final GatlingAdapter gatlingAdapter;
    private final TlsAwareHttpSupport tlsHttp;
    private final EnvTlsSupport envTlsSupport;

    public PerfCanaryPreflight(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                               PerfConfigReader perfConfigReader, GatlingAdapter gatlingAdapter,
                               TlsAwareHttpSupport tlsHttp, EnvTlsSupport envTlsSupport) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.perfConfigReader = perfConfigReader;
        this.gatlingAdapter = gatlingAdapter;
        this.tlsHttp = tlsHttp;
        this.envTlsSupport = envTlsSupport;
    }

    /**
     * Run the pre-flight for one launch. Returns null when the switch is off
     * (zero behaviour drift), otherwise the outcome map pinned into the run
     * snapshot: probes/answered counts, measured baseline p95 ms, the engine
     * the dispatch will use, and whether the Gatling compile was pre-warmed.
     * Throws a human-worded {@link ServiceException} on a refused launch.
     */
    public Map<String, Object> preflight(long projectId, long effectiveEnvId) {
        if (!perfConfigReader.canaryEnabled()) {
            return null;
        }
        Map<String, Object> env = jdbc.queryForList(
                        "SELECT base_url, config_json FROM tp_project_env WHERE id = :e",
                        new MapSqlParameterSource("e", effectiveEnvId))
                .stream().findFirst().orElse(null);
        String baseUrl = env == null || env.get("base_url") == null
                ? null : String.valueOf(env.get("base_url"));
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new ServiceException("金丝雀预检无法执行：该环境未配置基础地址，"
                    + "请在项目环境档案中补全后重试", BizErrorCode.VALIDATION_ERROR);
        }
        String host = hostOf(baseUrl);
        int requests = perfConfigReader.canaryRequests();
        int timeoutMs = perfConfigReader.canaryProbeTimeoutSeconds() * 1000;
        TlsPolicy policy = envTlsSupport.resolvePolicyFailSafe(
                env.get("config_json") == null ? null : String.valueOf(env.get("config_json")));

        // The probe fan-out is concurrent (virtual threads): N sequential probes
        // each burning the full timeout on a black-holed host would stack up to
        // N x timeout and overrun the admin→core bridge read timeout, so the
        // pre-flight would reject healthy launches by latency alone. Concurrency
        // of a handful of GETs is still "low load" — the point is liveness and a
        // baseline, not throughput.
        List<Long> answeredLatenciesMs = java.util.Collections.synchronizedList(new ArrayList<>());
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<?>> inFlight = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                inFlight.add(executor.submit(() -> {
                    long startedAt = System.nanoTime();
                    try {
                        // Any HTTP answer counts as liveness (non-2xx included —
                        // the probe stack returns status instead of throwing for
                        // 4xx/5xx).
                        tlsHttp.get(baseUrl, policy, timeoutMs);
                        answeredLatenciesMs.add((System.nanoTime() - startedAt) / 1_000_000L);
                    } catch (Exception networkFailure) {
                        // Unanswered probe: counted in the reject wording, never
                        // swallowed into a silent pass.
                    }
                }));
            }
            for (java.util.concurrent.Future<?> probe : inFlight) {
                try {
                    // Hard stop = per-request timeout + dispatch slack: a wedged
                    // connection must never keep the launch transaction open
                    // beyond the probe contract.
                    probe.get(timeoutMs / 1000L + 5L, java.util.concurrent.TimeUnit.SECONDS);
                } catch (Exception probeAbandoned) {
                    probe.cancel(true);
                }
            }
        }
        if (answeredLatenciesMs.isEmpty()) {
            throw new ServiceException("金丝雀预检未通过：目标 " + host + " 在 " + requests
                    + " 次低载探测中全部无响应（地址不可达或端口未开放），本次压测已取消，"
                    + "未产生运行记录；请确认目标环境服务已启动后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long baselineP95Ms = nearestRankP95(answeredLatenciesMs);
        int ceilingMs = perfConfigReader.canaryBaselineP95Ms();
        if (baselineP95Ms > ceilingMs) {
            throw new ServiceException("金丝雀预检未通过：目标 " + host + " 基线响应时间 p95="
                    + baselineP95Ms + "ms 已超过预检阈值 " + ceilingMs
                    + "ms（阈值可在系统配置 perf.canary.baseline-p95-ms 热更调整），"
                    + "本次压测已取消，未产生运行记录；请先排查目标环境性能状态",
                    BizErrorCode.VALIDATION_ERROR);
        }

        String engine = perfConfigReader.defaultEngine();
        boolean prewarmed = false;
        if ("GATLING".equals(engine)) {
            try {
                prewarmed = prewarmGatlingCompile(projectId, effectiveEnvId, baseUrl);
            } catch (ServiceException compileFailure) {
                throw new ServiceException("金丝雀预检未通过：Gatling 编译预热失败（"
                        + compileFailure.getMessage() + "），本次压测已取消，未产生运行记录",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        Map<String, Object> outcome = new LinkedHashMap<>();
        outcome.put("probes", requests);
        outcome.put("answered", answeredLatenciesMs.size());
        outcome.put("baselineP95Ms", baselineP95Ms);
        outcome.put("engine", engine);
        outcome.put("prewarmed", prewarmed);
        return outcome;
    }

    /**
     * Compile a throwaway one-step simulation through the real adapter so the
     * first javac run of this JVM (the expensive cold start: toolchain
     * initialisation + Gatling class loading) is paid here, before any run row
     * exists, instead of inside dispatch. A compile failure here is the same
     * failure dispatch would hit — rejecting before committing is strictly
     * better (zero residue) and names the pre-warm stage in the wording.
     */
    private boolean prewarmGatlingCompile(long projectId, long effectiveEnvId, String baseUrl) {
        Map<String, Object> warmView = new LinkedHashMap<>();
        warmView.put("id", 0L);
        warmView.put("project_id", projectId);
        warmView.put("env_id", effectiveEnvId);
        warmView.put("pressure_model", "CONSTANT_RPS");
        warmView.put("pressure_config", "{\"targetRps\":1,\"durationSeconds\":1}");
        warmView.put("login_strategy", null);
        warmView.put("dataset_id", null);
        try {
            warmView.put("steps_json", objectMapper.writeValueAsString(List.of(
                    Map.of("action", "http.request", "method", "GET", "url", baseUrl))));
        } catch (Exception impossible) {
            return false;
        }
        gatlingAdapter.compile(warmView);
        return true;
    }

    /** Nearest-rank p95 over the answered probe latencies (ascending, rank =
     *  ceil(0.95 x n), clamped into range). */
    static long nearestRankP95(List<Long> latenciesMs) {
        List<Long> sorted = new ArrayList<>(latenciesMs);
        sorted.sort(Long::compareTo);
        int rank = (int) Math.ceil(0.95 * sorted.size());
        return sorted.get(Math.min(Math.max(rank, 1), sorted.size()) - 1);
    }

    private static String hostOf(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl.trim());
            String host = uri.getHost();
            return host == null ? baseUrl : (uri.getPort() > 0 ? host + ":" + uri.getPort() : host);
        } catch (IllegalArgumentException malformed) {
            return baseUrl;
        }
    }
}
