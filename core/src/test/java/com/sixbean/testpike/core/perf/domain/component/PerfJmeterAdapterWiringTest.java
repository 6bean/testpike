package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * JMeter adapter wiring contract: the dispatcher routes the JMETER key to the
 * JMeter adapter (registration and table row land in the same package — a
 * value-range entry without its adapter row would silently degrade to an
 * honest rejection on every run), the launch stage self-checks the binary
 * boundary with human wording naming the engine and the configuration key,
 * and a shared-login strategy that JMeter threads cannot render truthfully
 * fails closed instead of faking per-user traffic.
 */
class PerfJmeterAdapterWiringTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * Deterministic stand-in for a present binary: the configured-path branch
     * wins and the launch stage never execs it (the subprocess belongs to the
     * perf_worker boundary), so these specs stay independent of an ambient
     * PATH that may or may not carry a real jmeter install.
     */
    private static final String PRESENT_BINARY = Path.of(System.getProperty("java.home"),
            "bin", System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "java.exe" : "java").toString();

    /** Routing table: JMETER resolves to the registered adapter instance. */
    @Test
    void dispatcherRoutesJmeterKeyToItsAdapter() {
        JmeterAdapter jmeter = mock(JmeterAdapter.class);
        PerfRunDispatcher dispatcher = new PerfRunDispatcher(
                mock(GatlingAdapter.class), mock(K6Adapter.class), jmeter,
                null, new PerfEngineResolver(null), null, MAPPER);
        assertSame(jmeter, dispatcher.adapterFor("JMETER"));
    }

    /** Missing binary = rejection naming the engine and the path config key. */
    @Test
    void launchWithoutBinaryFailsInHumanWording() {
        JmeterAdapter adapter = adapterWithBinary("/definitely/missing/jmeter",
                mock(PerfArtifactBundler.class));
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> adapter.launch("jmeter://7/plan.jmx", runContext(7L)));
        String wording = rejected.getMessage();
        assertTrue(wording.contains("JMeter"),
                "wording must name the engine, got: " + wording);
        assertTrue(wording.contains("testpike.perf.jmeter-bin") || wording.contains("PATH"),
                "wording must point at where to fix it (config key or PATH), got: " + wording);
    }

    /** A missing/unreadable compiled artifact is rejected before any launch. */
    @Test
    void launchWithUnreadableArtifactIsRejected() {
        PerfArtifactBundler bundler = mock(PerfArtifactBundler.class);
        when(bundler.handleReadable(anyString())).thenReturn(false);
        JmeterAdapter adapter = adapterWithBinary(PRESENT_BINARY, bundler);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> adapter.launch("perf://missing/plan.jmx", runContext(9L)));
        assertTrue(rejected.getMessage().contains("plan.jmx"),
                "wording must name the offending artifact, got: " + rejected.getMessage());
    }

    /**
     * Session-handle shape: with a readable artifact the launch stage returns
     * the jmeter:// session handle carrying the run id and the artifact leaf.
     * (The real subprocess `jmeter -n -t plan.jmx -l result.jtl` execution
     * belongs to the perf_worker collection chain, same boundary posture as
     * the k6 adapter — the control plane freezes the handle contract here.)
     */
    @Test
    void launchReturnsSessionHandleForReadableArtifact() {
        PerfArtifactBundler bundler = mock(PerfArtifactBundler.class);
        when(bundler.handleReadable(anyString())).thenReturn(true);
        JmeterAdapter adapter = adapterWithBinary(PRESENT_BINARY, bundler);
        String session = adapter.launch("artifacts/42/plan.jmx", runContext(42L));
        assertTrue(session.startsWith("jmeter://42/"),
                "handle must carry the engine scheme and the run id, got: " + session);
        assertTrue(session.endsWith("plan.jmx"));
    }

    /** SHARED login sessions need cross-thread sharing JMeter does not give per-VU by default. */
    @Test
    void sharedLoginStrategyFailsClosedNamingTheEngine() {
        JmeterAdapter adapter = adapterWithBinary("", mock(PerfArtifactBundler.class));
        Map<String, Object> scenario = scenarioView();
        scenario.put("login_strategy", "SHARED");
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> adapter.compile(scenario));
        assertTrue(rejected.getMessage().contains("SHARED"),
                "wording must name the rejected strategy, got: " + rejected.getMessage());
    }

    /** Happy path: a constant-RPS view compiles to a bundled .jmx artifact handle. */
    @Test
    void compileRendersJmxIntoTheArtifactBundle() {
        PerfArtifactBundler bundler = mock(PerfArtifactBundler.class);
        when(bundler.bundleEntries(anyLong(), anyString(), any())).thenReturn("artifacts/1/plan.jmx");
        JmeterAdapter adapter = adapterWithBinary("", bundler);
        String artifact = adapter.compile(scenarioView());
        assertTrue(artifact.endsWith("plan.jmx"),
                "compile returns the bundled artifact handle, got: " + artifact);
    }


    private static JmeterAdapter adapterWithBinary(String configured, PerfArtifactBundler bundler) {
        NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
        when(jdbc.queryForObject(anyString(),
                org.mockito.ArgumentMatchers.<org.springframework.jdbc.core.namedparam.SqlParameterSource>any(),
                any(Class.class)))
                .thenReturn("http://perf-target.local/api");
        return new JmeterAdapter(jdbc, MAPPER, mock(PerfStepActionGate.class),
                mock(PerfSlaJudge.class), bundler, configured);
    }

    private static Map<String, Object> scenarioView() {
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("project_id", 1L);
        scenario.put("env_id", 2L);
        scenario.put("pressure_model", "CONSTANT_RPS");
        ObjectNode config = MAPPER.createObjectNode();
        config.put("targetRps", 25);
        config.put("durationSeconds", 60);
        scenario.put("pressure_config", config.toString());
        ArrayNode steps = MAPPER.createArrayNode();
        ObjectNode step = steps.addObject();
        step.put("method", "GET");
        step.put("url", "/orders");
        scenario.put("steps_json", steps.toString());
        scenario.put("scenario_name", "jmeter-wiring-fixture");
        return scenario;
    }

    private static Map<String, Object> runContext(long runId) {
        Map<String, Object> ctx = new LinkedHashMap<>();
        ctx.put("runId", runId);
        return ctx;
    }
}
