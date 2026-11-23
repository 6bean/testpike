package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;

/**
 * JMeter third-engine onboarding contract on the core side: the dispatch
 * value range, the envelope engine gate, the routing table behind
 * {@link PerfRunDispatcher#dispatch} and the persisted CHECK constraint on
 * {@code tp_perf_scenario.engine} must all accept JMETER from one source.
 * <p>Gap this anchors: the dispatcher used a ternary ("K6" ? k6 : gatling)
 * which silently routed any unknown engine to Gatling — the exact drift a
 * third engine introduces — while the rejection wording hardcoded the two
 * engine names instead of enumerating the live value range.
 * <p>Routing scope: the table resolves every engine that has an adapter; an
 * engine inside the value range without a registered adapter is rejected
 * honestly (never silently substituted — D1 dispatch discipline).
 */
class PerfJmeterEngineContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Value range single source now carries the third engine. */
    @Test
    void engineValueRangeIncludesJmeter() {
        assertEquals(Set.of("K6", "GATLING", "JMETER"), PerfRunDispatchSupport.ENGINE_VALUES);
    }

    /**
     * Pure unit wiring (no DataSource): the run-row read past the engine gate
     * fails with an infrastructure error, never a ServiceException claiming
     * the engine itself is invalid.
     */
    @Test
    void envelopeGateAcceptsJmeterEngine() {
        PerfRunDispatchSupport support = new PerfRunDispatchSupport(null, MAPPER, null, null);
        String envelope = "{\"kind\":\"perf-run\",\"runId\":9,\"engine\":\"JMETER\","
                + "\"artifact\":\"scenario.jtl\"}";
        try {
            support.validateEnvelope(1L, envelope);
            fail("expected the run-row read to stop without a DataSource");
        } catch (ServiceException engineRejected) {
            fail("JMETER must pass the engine gate, got: " + engineRejected.getMessage());
        } catch (RuntimeException beyondGate) {
            assertNotNull(beyondGate);   // gate passed, stopped past it
        }
    }

    /**
     * Rejection wording enumerates the live value range — a hardcoded engine
     * list would drift the moment a third engine joins.
     */
    @Test
    void invalidEngineWordingEnumeratesValueRange() {
        PerfRunDispatchSupport support = new PerfRunDispatchSupport(null, MAPPER, null, null);
        String envelope = "{\"kind\":\"perf-run\",\"runId\":9,\"engine\":\"BOGUS\"}";
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> support.validateEnvelope(1L, envelope));
        assertTrue(rejected.getMessage().contains("JMETER"),
                "reject wording must enumerate the value range, got: " + rejected.getMessage());
    }

    /** Routing table: each registered adapter resolves by its engine key. */
    @Test
    void routingTableResolvesEachRegisteredEngine() {
        K6Adapter k6 = mock(K6Adapter.class);
        GatlingAdapter gatling = mock(GatlingAdapter.class);
        JmeterAdapter jmeter = mock(JmeterAdapter.class);
        PerfRunDispatcher dispatcher =
                new PerfRunDispatcher(gatling, k6, jmeter, null,
                        new PerfEngineResolver(null), null, MAPPER);
        assertSame(k6, dispatcher.adapterFor("K6"));
        assertSame(gatling, dispatcher.adapterFor("GATLING"));
        assertSame(jmeter, dispatcher.adapterFor("JMETER"));
    }

    /** Unknown engine = honest rejection with the supported list, never a silent fallback. */
    @Test
    void routingTableRejectsUnregisteredEngine() {
        K6Adapter k6 = mock(K6Adapter.class);
        GatlingAdapter gatling = mock(GatlingAdapter.class);
        JmeterAdapter jmeter = mock(JmeterAdapter.class);
        PerfRunDispatcher dispatcher =
                new PerfRunDispatcher(gatling, k6, jmeter, null,
                        new PerfEngineResolver(null), null, MAPPER);
        ServiceException rejected = assertThrows(ServiceException.class,
                () -> dispatcher.adapterFor("BOGUS"));
        assertTrue(rejected.getMessage().contains("BOGUS"),
                "wording must name the offending engine, got: " + rejected.getMessage());
    }

    /** Fresh-install bootstrap: the LAST ck_perf_scenario_engine definition includes JMETER. */
    @Test
    void schemaInitEngineCheckAllowsJmeter() throws IOException {
        String sql = Files.readString(repoRoot().resolve("db/schema-init.sql"));
        int at = sql.lastIndexOf("ADD CONSTRAINT ck_perf_scenario_engine");
        assertTrue(at >= 0, "engine CHECK must exist in the bootstrap file");
        String block = sql.substring(at, sql.indexOf(';', at));
        assertTrue(block.contains("'JMETER'"),
                "bootstrap engine CHECK must include JMETER, got: " + block);
    }

    /** Incremental path: some migration file widens the engine CHECK with JMETER. */
    @Test
    void aMigrationWidensEngineCheckWithJmeter() throws IOException {
        boolean widened = false;
        try (Stream<Path> files = Files.list(repoRoot().resolve("db/migration"))) {
            for (Path file : files.filter(p -> p.toString().endsWith(".sql")).toList()) {
                String sql = Files.readString(file);
                if (sql.contains("ADD CONSTRAINT ck_perf_scenario_engine")
                        && sql.contains("'JMETER'")) {
                    widened = true;
                    break;
                }
            }
        }
        assertTrue(widened, "a db/migration file must widen ck_perf_scenario_engine to JMETER");
    }

    /** Walk up from the surefire working directory to the repository root (catalog-test precedent). */
    private static Path repoRoot() {
        Path repoRoot = Path.of(System.getProperty("user.dir"));
        while (repoRoot != null && !Files.isDirectory(repoRoot.resolve("db/migration"))) {
            repoRoot = repoRoot.getParent();
        }
        assertNotNull(repoRoot, "repository root with db/migration must be reachable");
        return repoRoot;
    }
}
