package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JMeter third-engine adapter (W-d ruling, Apache-2.0 so the platform may
 * bundle it, while the executor posture stays self-installed like the k6
 * alternative engine — the binary is resolved at the process boundary, never
 * embedded in the platform JVM).
 * <p>Four-stage contract (frozen EngineAdapter surface):
 * ①compile = scenario view → {@link JmxTemplate} artifact (three pressure
 * shapes; anything unrendered fails closed naming the model);
 * ②launch = binary self-check (missing JMeter = ServiceException in human
 * wording naming the engine, the {@code testpike.perf.jmeter-bin} key and
 * PATH — never a silent engine switch) + artifact readability + session
 * handle; the real {@code jmeter -n -t plan.jmx -l result.jtl} subprocess run
 * belongs to the perf_worker collection chain (same boundary as k6);
 * ③collect = normalized frames flow back through perf_worker (JTL CSV lands
 * in the next package); an unknown/closed session yields no frames — faking
 * engine statistics is forbidden;
 * ④attribute = delegates {@link PerfSlaJudge} (three-gate judgment is a
 * single source shared with the other adapters; JMeter's own aggregates are
 * never adopted — D6 discipline).
 * <p>Rate-shape caveat surfaced inside every artifact ({@link
 * JmxTemplate#APPROXIMATION_NOTE}): throughput timers approximate the target
 * RPS per-second, so reports must disclose the approximation wording.
 */
@Component
public class JmeterAdapter implements EngineAdapter {

    /** Process-boundary lookup: executable name on PATH (no bundled binary). */
    static final String JMETER_BINARY = "jmeter";

    /** Entry name of the compiled plan inside the artifact bundle. */
    static final String JMX_ENTRY = "plan.jmx";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfStepActionGate stepActionGate;
    private final PerfSlaJudge slaJudge;
    private final PerfArtifactBundler artifactBundler;
    /** Explicit binary path (Spring key, empty = PATH lookup; user config, no inline credentials apply). */
    private final String configuredBinary;

    public JmeterAdapter(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                         PerfStepActionGate stepActionGate, PerfSlaJudge slaJudge,
                         PerfArtifactBundler artifactBundler,
                         @Value("${testpike.perf.jmeter-bin:}") String configuredBinary) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.stepActionGate = stepActionGate;
        this.slaJudge = slaJudge;
        this.artifactBundler = artifactBundler;
        this.configuredBinary = configuredBinary == null ? "" : configuredBinary.trim();
    }

    @Override
    public String compile(Map<String, Object> scenario) {
        String model = PerfScenarioReadSupport.text(scenario, "pressure_model");
        JsonNode config = readJson(PerfScenarioReadSupport.text(scenario, "pressure_config"), "压力参数");
        JsonNode steps = readJson(PerfScenarioReadSupport.text(scenario, "steps_json"), "请求步骤");
        // Honest-shape gate (same fail-closed family as the k6 adapter): a
        // SHARED/PER_SESSION login strategy needs one session reused across
        // virtual users; JMeter thread groups keep per-thread state by default,
        // so compiling it would fake the declared traffic shape.
        PerfScenarioReadSupport.rejectSharedSessionStrategy(scenario,
                " 需要跨虚拟用户共享会话，JMeter 线程组默认按线程隔离会话，"
                        + "无法真实编译该形态（静默降级为每用户登录会伪造流量口径）"
                        + "——请改用共享策略引擎或调整为每用户登录");
        // UI actions cannot be rendered into a JMX sampler tree: reject them
        // at compile instead of dropping them silently from the artifact.
        stepActionGate.rejectUiActions(steps, false, false);
        requireHttpSteps(steps);

        long envId = ((Number) scenario.get("env_id")).longValue();
        String baseUrl = jdbc.queryForObject("SELECT base_url FROM tp_project_env WHERE id = :e",
                new MapSqlParameterSource("e", envId), String.class);
        String xml = JmxTemplate.render(model, config, steps, baseUrl);

        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(JMX_ENTRY, xml.getBytes(StandardCharsets.UTF_8));
        String scriptName = PerfScenarioReadSupport.text(scenario, "scenario_name") == null
                ? "jmeter-run" : PerfScenarioReadSupport.text(scenario, "scenario_name");
        return artifactBundler.bundleEntries(
                ((Number) scenario.get("project_id")).longValue(), scriptName, entries);
    }

    @Override
    public String launch(String artifact, Map<String, Object> runContext) {
        Path binary = resolveBinary();
        if (binary == null) {
            throw new ServiceException("JMeter 引擎二进制缺失（进程边界口径=执行机自装，"
                    + "PATH 未找到 jmeter）：请在执行机安装 Apache JMeter 并加入 PATH，"
                    + "或配置 testpike.perf.jmeter-bin 指向可执行文件后重试；"
                    + "禁止静默切换 K6/Gatling 顶替 JMeter 引擎", BizErrorCode.VALIDATION_ERROR);
        }
        if (!artifactBundler.handleReadable(artifact)) {
            throw new ServiceException("JMeter 压测无法启动：编译产物不存在或不可读「"
                    + artifact + "」——请到「压测工作台」相应场景行重新「保存场景」触发编译，成功后再「发起压测」", BizErrorCode.VALIDATION_ERROR);
        }
        // The subprocess `jmeter -n -t plan.jmx -l result.jtl` belongs to the
        // perf_worker collection chain; the control plane freezes the handle.
        return "jmeter://" + runContext.get("runId") + "/"
                + Path.of(artifact.replace('\\', '/')).getFileName();
    }

    @Override
    public List<PerfMetricFrame> collect(String engineSession) {
        // Stage three: normalized JTL frames land via the perf_worker
        // ingestion chain (executor package), so this call stays a single
        // sourced placeholder until that path is wired for this engine.
        return PerfScenarioReadSupport.collectUntilIngested(engineSession, "jmeter://");
    }

    @Override
    public PerfAttribution attribute(Map<String, Object> run, List<PerfMetricFrame> frames) {
        return slaJudge.judge(run, frames);
    }


    /**
     * Binary resolution single source: the configured path wins when set and
     * must itself be executable (a misconfiguration fails closed rather than
     * degrading to a different install); otherwise walk PATH for "jmeter".
     */
    Path resolveBinary() {
        if (!configuredBinary.isEmpty()) {
            Path explicit = Path.of(configuredBinary);
            return Files.isExecutable(explicit) ? explicit : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        for (String dir : path.split(java.io.File.pathSeparator)) {
            Path candidate = Path.of(dir, JMETER_BINARY);
            if (Files.isExecutable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void requireHttpSteps(JsonNode steps) {
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            throw new ServiceException("JMeter 场景缺少请求步骤，无法生成 JMX 产物"
                    + "——请在「压测工作台」打开该场景的编辑向导，在「请求步骤」表里至少添加一行 HTTP 请求", BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode step : steps) {
            String url = step.path("url").asText("");
            if (url.isBlank()) {
                throw new ServiceException("JMeter 引擎仅能编译 HTTP 请求步骤"
                        + "（存在缺少请求路径的步骤），请在「请求步骤」表为每行填写请求路径（如 https://api.example.com/products），或改用 UI 场景引擎",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    private JsonNode readJson(String raw, String what) {
        return PerfScenarioReadSupport.readJson(objectMapper, raw, what);
    }
}
