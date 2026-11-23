package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


@Component
public class PerfRunDispatchSupport {

    private static final Logger log = LoggerFactory.getLogger(PerfRunDispatchSupport.class);

    
    public static final Set<String> ENGINE_VALUES = Set.of("K6", "GATLING", "JMETER");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfConfigReader configReader;
    private final com.sixbean.testpike.core.project.domain.component.EnvTlsSupport envTlsSupport;

    public PerfRunDispatchSupport(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                  PerfConfigReader configReader,
                                  com.sixbean.testpike.core.project.domain.component.EnvTlsSupport envTlsSupport) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.configReader = configReader;
        this.envTlsSupport = envTlsSupport;
    }

    
    public JsonNode validateEnvelope(long projectId, String actionJson) {
        JsonNode env = parseEnvelope(actionJson);
        if (!"perf-run".equals(env.path("kind").asText())) {
            throw new ServiceException("压测任务信封 kind 必须为 perf-run（当前="
                    + env.path("kind").asText("") + "）", BizErrorCode.VALIDATION_ERROR);
        }
        long runId = env.path("runId").asLong(0);
        if (runId <= 0) {
            throw new ServiceException("压测任务信封缺少有效 runId（须指向已发起的压测运行）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String engine = env.path("engine").asText("");
        if (!ENGINE_VALUES.contains(engine)) {
            throw new ServiceException("压测任务引擎标识非法（" + (engine.isEmpty() ? "空" : engine)
                    + "），可选=" + String.join("/", ENGINE_VALUES), BizErrorCode.VALIDATION_ERROR);
        }
        Integer exists = jdbc.queryForObject("SELECT count(*) FROM tp_perf_run "
                        + "WHERE id = :r AND project_id = :p",
                new MapSqlParameterSource("r", runId).addValue("p", projectId), Integer.class);
        if (exists == null || exists == 0) {
            throw new ServiceException("压测运行不存在或不属于该项目，无法建派发任务",
                    BizErrorCode.NOT_FOUND);
        }
        return env;
    }

    
    public Map<String, Object> assembleForClaim(long projectId, String actionJson) {
        try {
            JsonNode env = parseEnvelope(actionJson);
            long runId = env.path("runId").asLong(0);
            if (runId <= 0) {
                log.warn("压测派发任务信封缺 runId（脏单）——perf 上下文不下发，项目 {}", projectId);
                return null;
            }
            Map<String, Object> snapshot = jdbc.queryForList(
                            "SELECT pressure_snapshot::text AS snapshot FROM tp_perf_run "
                                    + "WHERE id = :r AND project_id = :p",
                            new MapSqlParameterSource("r", runId).addValue("p", projectId))
                            .stream().findFirst().orElse(null);
            if (snapshot == null) {
                log.warn("压测运行 {}（项目 {}）已删或归属不符——perf 上下文不下发，"
                        + "执行器按缺上下文原因终止", runId, projectId);
                return null;
            }
            Map<String, Object> perf = new LinkedHashMap<>();
            perf.put("runId", runId);
            perf.put("engine", env.path("engine").asText(""));
            perf.put("artifact", env.path("artifact").asText(""));
            perf.put("pressureSnapshot", snapshot.get("snapshot"));
            // b2: flush-window cadence for in-press partial frames (K8 key
            // perf.frames-flush-interval-seconds, clamped at the reader — the
            // executor consumes it as-is, old executors ignore unknown keys).
            perf.put("framesFlushSeconds", configReader.framesFlushSeconds());
            // : the ingestion volume gate value rides the claim so the
            // executor chunks every shipped row to the gate's own number (single
            // numeric source = PerfMetricServiceImpl, no duplicated literal).
            perf.put("maxShipFrames",
                    com.sixbean.testpike.core.perf.domain.service.impl
                            .PerfMetricServiceImpl.MAX_BATCH_FRAMES);
            // B-10 : the resolved TLS policy of the environment this
            // run presses rides the claim envelope (same shape + same plaintext-
            // material discipline as the apitest envelope tail). The executor
            // injects it per engine (k6 CLI flag / Gatling trust store); tier
            // re-deciding on the executor side is forbidden (single source =
            // common TlsContextResolver through EnvTlsSupport). Resolution is
            // live from the env profile (not the snapshot label): the pinned
            // tlsMode is the report-face truth of what tier was DECLARED at
            // launch, while the material must be current to work at press time.
            tlsPolicyOf(snapshot.get("snapshot")).ifPresent(policy -> perf.put("tlsPolicy", policy));
            return perf;
        } catch (Exception e) {
            log.warn("压测派发上下文装配失败（项目 {}）：{}", projectId, e.getMessage());
            return null;
        }
    }

    /**
     * Envelope budget read point (A-14): the dispatch-side budgetMs the claim path
     * hands to the executor and the timeout sweep judges perf rows by. Missing key,
     * non-positive value, or a non-perf/dirty envelope = 0, meaning "no budget
     * claim" — callers must fall back to the global case timeout (zero drift).
     */
    public long budgetMsOf(String actionJson) {
        try {
            JsonNode env = objectMapper.readTree(actionJson);
            if (!"perf-run".equals(env.path("kind").asText())) {
                return 0L;
            }
            long budget = env.path("budgetMs").asLong(0);
            return budget > 0 ? budget : 0L;
        } catch (Exception unparsable) {
            return 0L;
        }
    }

    /**
     * Envelope runId read point (task-badge deep link): a perf row (task_type=5)
     * exposes the run it pins so the execution-task list can link to the run
     * detail page without re-parsing the envelope anywhere else (single source).
     * Mirrors the {@code budgetMsOf} fail-safe shape: a non-perf kind, a dirty
     * payload, or a missing/non-positive runId all yield null — "no badge
     * target" — and never throw, so the shared row mapper keeps list/claim
     * reads intact (zero drift for every other task type).
     */
    public Long runIdOf(String actionJson) {
        try {
            JsonNode env = objectMapper.readTree(actionJson);
            if (!"perf-run".equals(env.path("kind").asText())) {
                return null;
            }
            long runId = env.path("runId").asLong(0);
            return runId > 0 ? runId : null;
        } catch (Exception unparsable) {
            return null;
        }
    }

    /**
     * Resolve the claim-side TLS policy for the environment pinned in this run's
     * snapshot (B-10). Snapshot → envId → config_json → {@code EnvTlsSupport
     * .resolvePolicyFailSafe} (single-source tier + decrypted material) → the
     * apitest envelope shape {mode, caPem?, clientCertPem?, clientKeyPem?}.
     * Unparsable snapshot / absent env row = empty (no key trails, old executor
     * shape, honest strict-default consumption on the engine side).
     * ⚠️ The map carries plaintext material — claim envelope only, never logs.
     */
    private java.util.Optional<Map<String, Object>> tlsPolicyOf(Object rawSnapshot) {
        long envId;
        try {
            envId = objectMapper.readTree(String.valueOf(rawSnapshot)).path("envId").asLong(0);
        } catch (Exception unparsable) {
            return java.util.Optional.empty();
        }
        if (envId <= 0) {
            return java.util.Optional.empty();
        }
        String configJson = jdbc.queryForList(
                        "SELECT config_json::text AS cfg FROM tp_project_env WHERE id = :e",
                        new MapSqlParameterSource("e", envId))
                .stream().findFirst().map(row -> row.get("cfg")).map(String::valueOf).orElse(null);
        com.sixbean.testpike.common.util.TlsPolicy policy =
                envTlsSupport.resolvePolicyFailSafe(configJson);
        Map<String, Object> node = new LinkedHashMap<>();
        node.put("mode", policy.mode().raw());
        if (policy.caPem() != null) {
            node.put("caPem", policy.caPem());
        }
        if (policy.hasClientCertPair()) {
            node.put("clientCertPem", policy.clientCertPem());
            node.put("clientKeyPem", policy.clientKeyPem());
        }
        return java.util.Optional.of(node);
    }

    private JsonNode parseEnvelope(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            throw new ServiceException("压测任务信封不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        try {
            JsonNode node = objectMapper.readTree(actionJson);
            if (!node.isObject()) {
                throw new ServiceException("压测任务信封必须是 JSON 对象",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return node;
        } catch (ServiceException e) {
            throw e;
        } catch (Exception e) {
            throw new ServiceException("压测任务信封不是合法 JSON：" + e.getMessage(),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }
}
