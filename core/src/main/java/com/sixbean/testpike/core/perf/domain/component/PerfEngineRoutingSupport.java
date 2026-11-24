package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Load-engine capability routing on the core consumer side (GA-4): a perf-run
 * candidate may only reach an executor whose self-probed capability face
 * reports the envelope engine, mirroring the app-auto claim-side posture —
 * candidate-level skip (never an exception, pool/sticky gates shape) and an
 * order-side early rejection so a launch does not silently queue forever when
 * an online fleet exists but nobody in it can start the engine.
 * <p>Envelope engine values are single-sourced from
 * {@link PerfRunDispatchSupport#ENGINE_VALUES} (no second dialect); the
 * capability-face match delegates to
 * {@link AppAutoDispatchSupport#covers} (no copied matching logic).
 * <p>Honest-absence semantics: an executor that never reported a capability
 * face has unknown capability = perf dispatch refused (false claims would
 * land a load run on a machine that cannot start it). One deliberate
 * difference from the app-auto gate: an EMPTY online fleet is not a rejection
 * — that is "no machine yet" (the queue-note contract: the run auto-starts
 * once a node comes online), while "online N, capable 0" is the actionable
 * missing-engine case this gate rejects.
 */
@Component
public class PerfEngineRoutingSupport {

    private static final Logger log = LoggerFactory.getLogger(PerfEngineRoutingSupport.class);

    /** perf-run envelope kind (same literal PerfRunDispatchSupport guards). */
    private static final String ENVELOPE_KIND = "perf-run";

    private final ObjectMapper objectMapper;
    private final ExecutorService executorService;
    private final AppAutoDispatchSupport appAutoDispatchSupport;

    public PerfEngineRoutingSupport(ObjectMapper objectMapper,
                                    ExecutorService executorService,
                                    AppAutoDispatchSupport appAutoDispatchSupport) {
        this.objectMapper = objectMapper;
        this.executorService = executorService;
        this.appAutoDispatchSupport = appAutoDispatchSupport;
    }

    /**
     * Claim-candidate decision: a perf-run candidate whose engine is not on
     * this executor's capability face is skipped (queued for someone capable),
     * everything else passes through untouched.
     *
     * @param actionJson   candidate envelope (dirty/non-perf = pass through —
     *                     the dispatch-side validator owns dirty orders)
     * @param capabilities this executor's reported face (null/empty = unknown)
     * @return true = this executor must not claim this candidate
     */
    public boolean shouldSkipForExecutor(String actionJson, Map<String, Object> capabilities) {
        String engine = perfEnvelopeEngine(actionJson);
        if (engine == null) {
            return false;   // non-perf or dirty envelope: zero drift, consumer side owns it
        }
        if (capabilities == null || capabilities.isEmpty()) {
            return true;    // capability unknown = honest absence, refuse the load run
        }
        return !appAutoDispatchSupport.covers(capabilities, "engines", engine);
    }

    /**
     * Order-side human wording: non-null only when executors are online but
     * none reports the engine this run needs (empty fleet = null, the run
     * legitimately queues). Consumed by createPerfRunTask before the insert,
     * mirroring the app-auto health-gate posture (reject = zero rows written).
     */
    public String explainNoCapablePerfExecutorForEnvelope(String actionJson) {
        String engine = perfEnvelopeEngine(actionJson);
        if (engine == null) {
            return null;
        }
        List<Map<String, Object>> online = executorService.onlineCapabilities();
        if (online.isEmpty()) {
            return null;   // no machine online yet = queue semantics (see class note)
        }
        boolean capable = online.stream().anyMatch(
                caps -> appAutoDispatchSupport.covers(caps, "engines", engine));
        if (capable) {
            return null;
        }
        return "压测运行需要引擎 " + engine + "——当前在线执行器 " + online.size()
                + " 台，具备该能力的 0 台，当前没有能执行该压测的执行机。请在压测执行机安装对应引擎"
                + "（K6=k6 二进制入 PATH；GATLING=java 17+ 与 GATLING_LIB 引擎 jar 目录）"
                + "并重启执行器重新注册。";
    }

    /**
     * Envelope engine projection single point: kind=perf-run with an engine
     * inside {@link PerfRunDispatchSupport#ENGINE_VALUES} yields the code;
     * anything else (parse failure, non-perf kind, unknown engine) yields
     * null = "not a routing decision" (honest absence, never guesses).
     */
    private String perfEnvelopeEngine(String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return null;
        }
        try {
            JsonNode env = objectMapper.readTree(actionJson);
            if (!env.isObject() || !ENVELOPE_KIND.equals(env.path("kind").asText())) {
                return null;
            }
            String engine = env.path("engine").asText("");
            return PerfRunDispatchSupport.ENGINE_VALUES.contains(engine) ? engine : null;
        } catch (Exception unparsable) {
            log.debug("perf capability routing skipped unparsable envelope: {}",
                    unparsable.getMessage());
            return null;
        }
    }
}
