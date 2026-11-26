package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * JMeter JMX artifact renderer (W-d third engine, template object — the
 * scenario view is turned into a well-formed XML document through named
 * slots, never raw string concatenation of user content into XML syntax
 * beyond the escaped values below).
 * <p>Serialization form is pinned against the real engine, not from memory:
 * the Precise Throughput Timer property names and XML shapes were verified
 * with javap on JMeter 5.6.3 plus a live {@code jmeter -n -t} load —
 * throughput/throughputPeriod/duration/exactLimit/allowedThroughputSurplus/
 * randomSeed/batchSize/batchThreadDelay, doubleProp wrappers carry name and
 * value children only (a {@code <class>} child aborts test-plan load),
 * guiclass is {@code TestBeanGUI}. No inline ResultCollector is emitted:
 * {@code jmeter -n -l} writes the JTL itself, an embedded listener previously
 * blew up the same load path.
 * <p>Pressure model mapping (the compiled set is exactly the three shapes the
 * ruling scopes; anything else fails closed naming the model — a silently
 * degraded curve would report the wrong traffic shape):
 * <ul>
 *   <li>CONSTANT_RPS — one scheduled thread group + precise throughput timer
 *       held at the target rate from t=0 (period = {@link #PERIOD_SECONDS});</li>
 *   <li>RAMP — the rise window rendered as piecewise-constant sub-legs
 *       (staircase of at most {@link #RAMP_WINDOW_SECONDS} each, midpoint
 *       rate), then the peak held for the remainder — an approximation this
 *       artifact discloses, never a silently different curve;</li>
 *   <li>SPIKE — the four segments timed by the shared
 *       {@link PerfSpikeSegments} source (single derivation for both engines,
 *       R40), rise/fall stepped the same way, offset-scheduled so the
 *       windows tile the declared duration exactly.</li>
 * </ul>
 * <p>Every thread group nests its own timer and the full sampler set inside
 * its hashTree — samplers as siblings of the thread group are never executed
 * (that shape produced the frames=0 run outcome this renderer fixes).
 * <p>Approximation disclosure: JMeter's throughput timers approach a target
 * rate with smoothing (BSS behaviour) and per-second quantisation, so the
 * instantaneous rate hovers around the declared value rather than matching an
 * exact arrival schedule. {@link #APPROXIMATION_NOTE} is embedded in every
 * artifact verbatim and is the single-source wording reports/wizards reuse.
 */
public final class JmxTemplate {

    /** Rate-approximation disclosure (verbatim, travels inside each JMX). */
    public static final String APPROXIMATION_NOTE =
            "JMeter 速率近似口径：Precise Throughput Timer 按秒粒度以 BSS 平滑逼近目标 RPS，"
            + "实际瞬时速率围绕目标值波动，不等于精确到达率调度。";

    /** Throughput period (seconds): throughput counts samples per window. */
    private static final int PERIOD_SECONDS = 60;

    /** Max width of one staircase window for a ramping segment, in seconds. */
    private static final int RAMP_WINDOW_SECONDS = 15;

    /** Fixed arrival-schedule seed: reruns of one scenario stay comparable. */
    private static final long FIXED_SEED = 0L;

    /** Surplus factor the engine tolerates around the scheduled arrivals. */
    private static final String SURPLUS_DEFAULT = "1.0";

    private JmxTemplate() {
    }

    /**
     * Render the JMX document for one scenario view fragment.
     *
     * @param model   pressure model key (CONSTANT_RPS / RAMP / SPIKE)
     * @param config  parsed pressure_config JSON for that model
     * @param steps   parsed steps JSON (one sampler per declared step)
     * @param baseUrl environment base url relative step paths resolve against
     */
    public static String render(String model, JsonNode config, JsonNode steps, String baseUrl) {
        int duration = config.path("durationSeconds").asInt(0);
        if (duration <= 0) {
            throw new ServiceException("JMeter 压测时长未声明（pressure_config.durationSeconds"
                    + " 缺失或为 0），无法生成可执行的 JMX 产物——请在「压测工作台」的场景压力配置中"
                    + "填写时长（示例：durationSeconds=300）", BizErrorCode.VALIDATION_ERROR);
        }
        String samplers = renderSamplers(steps, baseUrl);
        StringBuilder legs = new StringBuilder();
        switch (model == null ? "" : model) {
            case "CONSTANT_RPS" -> {
                int rps = config.path("targetRps").asInt(0);
                appendLeg(legs, "constant", 0, duration, rps, rps, false, samplers);
            }
            case "RAMP" -> {
                int base = config.path("baseRps").asInt(0);
                int peak = config.path("peakRps").asInt(0);
                int declaredRamp = config.path("rampSeconds").asInt(0);
                int ramp = declaredRamp > 0 ? Math.min(declaredRamp, duration) : duration;
                appendLeg(legs, "ramp", 0, ramp, base, peak, true, samplers);
                int hold = duration - ramp;
                if (hold > 0) {
                    appendLeg(legs, "hold", ramp, hold, peak, peak, false, samplers);
                }
            }
            case "SPIKE" -> appendSpikeLegs(legs, config, duration, samplers);
            default -> throw new ServiceException("JMeter 引擎本期未编译压力形态 "
                    + (model == null ? "（空）" : model)
                    + "，当前支持=CONSTANT_RPS/RAMP/SPIKE——请改用已支持的压测形态或选择其它引擎"
                    + "（正确写法：CONSTANT_RPS）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return document(legs.toString());
    }

    /** SPIKE: the same four-segment arithmetic both engines consume (R40 single source). */
    private static void appendSpikeLegs(StringBuilder legs, JsonNode config, int duration,
                                        String samplers) {
        int base = config.path("baseRps").asInt(0);
        int peak = config.path("peakRps").asInt(0);
        PerfSpikeSegments segments = PerfSpikeSegments.derive(duration);
        int offset = 0;
        appendLeg(legs, "rise", offset, segments.riseSeconds(), base, peak, true, samplers);
        offset += segments.riseSeconds();
        appendLeg(legs, "hold", offset, segments.holdSeconds(), peak, peak, false, samplers);
        offset += segments.holdSeconds();
        appendLeg(legs, "fall", offset, segments.fallSeconds(), peak, base, true, samplers);
        offset += segments.fallSeconds();
        appendLeg(legs, "tail", offset, segments.tailSeconds(), base, base, false, samplers);
    }

    /**
     * One logical leg scheduled at an offset. A steady leg is a single thread
     * group; a ramping leg becomes a staircase of short sub-legs whose
     * midpoint rates interpolate start to target — the piecewise approximation
     * the artifact discloses (the timer itself has no ramp primitive).
     */
    private static void appendLeg(StringBuilder out, String legName, int offsetSeconds,
                                  int legSeconds, int startRps, int targetRps, boolean ramping,
                                  String samplers) {
        if (legSeconds <= 0) {
            return;
        }
        if (!ramping || startRps == targetRps) {
            appendScheduledGroup(out, legName, offsetSeconds, legSeconds, targetRps, samplers);
            return;
        }
        int windows = Math.max((int) Math.ceil((double) legSeconds / RAMP_WINDOW_SECONDS), 1);
        int window = (int) Math.ceil((double) legSeconds / windows);
        for (int i = 0; i < windows; i++) {
            int len = Math.min(window, legSeconds - i * window);
            if (len <= 0) {
                break;
            }
            double midpoint = startRps + (targetRps - startRps) * (i + 0.5) / windows;
            int rate = Math.max((int) Math.round(midpoint), 1);
            appendScheduledGroup(out, legName + "-" + (i + 1), offsetSeconds + i * window,
                    len, rate, samplers);
        }
    }

    /** Thread group + its hashTree holding the timer and every step sampler. */
    private static void appendScheduledGroup(StringBuilder out, String name, int delaySeconds,
                                             int legSeconds, int rateRps, String samplers) {
        out.append("""
              <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="jmeter-NAME" enabled="true">
                <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
                <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
                  <boolProp name="LoopController.continue_forever">false</boolProp>
                  <intProp name="LoopController.loops">-1</intProp>
                </elementProp>
                <stringProp name="ThreadGroup.num_threads">THREADS</stringProp>
                <stringProp name="ThreadGroup.ramp_time">1</stringProp>
                <boolProp name="ThreadGroup.scheduler">true</boolProp>
                <stringProp name="ThreadGroup.delay">DELAY</stringProp>
                <stringProp name="ThreadGroup.duration">DUR</stringProp>
              </ThreadGroup>
              <hashTree>
                <PreciseThroughputTimer guiclass="TestBeanGUI" testclass="PreciseThroughputTimer" testname="throughput-NAME" enabled="true"><intProp name="throughputPeriod">PERIOD</intProp><longProp name="duration">DUR</longProp><intProp name="exactLimit">1</intProp><doubleProp><name>throughput</name><value>RATE_PER_WINDOW</value></doubleProp><doubleProp><name>allowedThroughputSurplus</name><value>SURPLUS</value></doubleProp><longProp name="randomSeed">SEED</longProp><intProp name="batchSize">1</intProp><intProp name="batchThreadDelay">0</intProp></PreciseThroughputTimer>
                <hashTree/>
            SAMPLERS
              </hashTree>
            """.replace("NAME", name)
                .replace("THREADS", Integer.toString(Math.max(rateRps, 1)))
                .replace("DELAY", Integer.toString(delaySeconds))
                .replace("DUR", Integer.toString(legSeconds))
                .replace("PERIOD", Integer.toString(PERIOD_SECONDS))
                .replace("RATE_PER_WINDOW", rateRps * PERIOD_SECONDS + ".0")
                .replace("SURPLUS", SURPLUS_DEFAULT)
                .replace("SEED", Long.toString(FIXED_SEED))
                .replace("SAMPLERS", samplers));
    }

    /** One HTTP sampler per declared step; values are XML-escaped, never raw. */
    private static String renderSamplers(JsonNode steps, String baseUrl) {
        StringBuilder samplers = new StringBuilder();
        if (steps == null || !steps.isArray() || steps.isEmpty()) {
            throw new ServiceException("JMeter 场景缺少请求步骤（steps 为空），"
                    + "无法生成 JMX 产物——请在场景中至少配置一个请求步骤"
                    + "（示例：一个 GET 请求步骤）", BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode step : steps) {
            String method = step.path("method").asText("GET");
            // Canonical DSL slot is "url" (same key K6/Gatling templates and
            // the step-action gate read); relative paths resolve against the
            // environment base url, absolute ones stand on their own.
            String rawUrl = step.path("url").asText("");
            String body = step.path("body").asText("");
            URI uri = resolveTarget(baseUrl, rawUrl);
            String path = pathWithQuery(uri);
            boolean hasBody = !body.isBlank();
            samplers.append("""
                <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="STEP_NAME" enabled="true">
                  <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" enabled="true">
                    <collectionProp name="Arguments.arguments">BODY_ARGUMENT</collectionProp>
                  </elementProp>
                  <stringProp name="HTTPSampler.domain">DOMAIN</stringProp>
                  <stringProp name="HTTPSampler.port">PORT</stringProp>
                  <stringProp name="HTTPSampler.protocol">PROTOCOL</stringProp>
                  <stringProp name="HTTPSampler.path">PATH</stringProp>
                  <stringProp name="HTTPSampler.method">STEP_METHOD</stringProp>
                  <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
                  <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
                  POST_RAW
                </HTTPSamplerProxy>
                <hashTree/>
                """.replace("STEP_NAME", escape(method + " " + uri))
                        .replace("BODY_ARGUMENT", hasBody ? bodyArgument(body) : "")
                        .replace("DOMAIN", escape(uri.getHost()))
                        .replace("PORT", uri.getPort() > 0 ? Integer.toString(uri.getPort()) : "")
                        .replace("PROTOCOL", escape(uri.getScheme()))
                        .replace("PATH", escape(path))
                        .replace("STEP_METHOD", escape(method))
                        .replace("POST_RAW", hasBody
                                ? "<boolProp name=\"HTTPSampler.postBodyRaw\">true</boolProp>" : ""));
        }
        return samplers.toString();
    }

    /** Raw-body transport shape the engine's HTTP sampler understands. */
    private static String bodyArgument(String body) {
        return """
            <elementProp name="BodyARG" elementType="HTTPArgument">
              <boolProp name="HTTPArgument.always_encode">false</boolProp>
              <stringProp name="Argument.value">BODY</stringProp>
              <stringProp name="Argument.metadata">=</stringProp>
            </elementProp>
            """.replace("BODY", escape(body));
    }

    /**
     * Resolve one step target into an absolute URI: absolute urls pass
     * through, relative paths are joined onto the environment base url. An
     * unresolvable target fails closed naming the offending value — guessing
     * (like stuffing a full url into the path slot and hoping) is how the
     * previous artifact silently dropped every request.
     */
    private static URI resolveTarget(String baseUrl, String rawUrl) {
        String trimmed = rawUrl.trim();
        String candidate;
        if (trimmed.contains("://")) {
            candidate = trimmed;
        } else {
            String base = baseUrl == null ? "" : baseUrl.trim();
            if (base.isBlank()) {
                throw new ServiceException("JMeter 步骤地址是相对路径（" + trimmed
                        + "）但压测环境未配置 baseUrl——请在「环境配置」中补充基础地址"
                        + "或把步骤写成完整 URL（示例：https://api.example.com）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            String joined = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
            candidate = joined + (trimmed.startsWith("/") ? trimmed : "/" + trimmed);
        }
        URI uri;
        try {
            uri = new URI(candidate);
        } catch (URISyntaxException e) {
            throw new ServiceException("JMeter 步骤目标地址无法解析：" + candidate
                    + "——请检查环境 baseUrl 与步骤 url 的拼接结果"
                    + "（示例：baseUrl=https://api.example.com，url=/v1/projects）", BizErrorCode.VALIDATION_ERROR);
        }
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new ServiceException("JMeter 步骤目标地址缺少协议或主机名：" + candidate
                    + "——请检查环境 baseUrl 与步骤 url 的拼接结果"
                    + "（示例：baseUrl=https://api.example.com，url=/v1/projects）", BizErrorCode.VALIDATION_ERROR);
        }
        return uri;
    }

    /** Path slot content: raw path (or "/" when absent) plus the query string. */
    private static String pathWithQuery(URI uri) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        return uri.getRawQuery() == null ? path : path + "?" + uri.getRawQuery();
    }

    private static String document(String legs) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <jmeterTestPlan version="1.9" properties="5.0" jmeter="5.6.3">
                  <!-- APPROX_NOTE -->
                  <hashTree>
                    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="testpike-jmeter" enabled="true">
                      <boolProp name="TestPlan.functional_mode">false</boolProp>
                      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
                      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
                        <collectionProp name="Arguments.arguments"/>
                      </elementProp>
                    </TestPlan>
                    <hashTree>
                LEGS
                    </hashTree>
                  </hashTree>
                </jmeterTestPlan>
                """.replace("APPROX_NOTE", APPROXIMATION_NOTE)
                        .replace("LEGS", legs);
    }

    /** XML-escape user-controlled values before they enter the artifact. */
    private static String escape(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
