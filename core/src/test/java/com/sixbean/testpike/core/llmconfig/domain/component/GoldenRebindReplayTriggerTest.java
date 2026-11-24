package com.sixbean.testpike.core.llmconfig.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.llmconfig.domain.component.GoldenRebindReplayTrigger;
import com.sixbean.testpike.core.llmconfig.infrastructure.remote.client.GoldenReplayRouteClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.when;

/**
 * 3: a project model-route switch must fan the change out to the
 * ai-service route-axis golden replay for every domain whose golden pack is
 * registered, off the save path (no model call inside the transaction, no
 * propagation of replay failures back into the binding save).
 *
 * <p>Covers: payload shape per released (FULL) family with gray rows excluded;
 * identical routes dispatched nowhere; unregistered/invalid pack entries
 * skipped; one failing target does not stop the remaining fan-out and never
 * throws back to the caller. The ai-service leg is a port stub (L2 boundary
 * isolation — the live leg rides the later L3 window), config reads are stubbed
 * to a fixed mapping while the template rows stay real dev-DB fixtures.
 */
@SpringBootTest(classes = CoreApplication.class)
class GoldenRebindReplayTriggerTest {

    /** Fixture domains, cleaned by domain in teardown (ledger ⑧ posture). */
    private static final String DOMAIN_FULL = "ut-rebind-route";
    private static final String DOMAIN_FAILING = "ut-rebind-failing";

    private final List<Map<String, Object>> calls = new CopyOnWriteArrayList<>();

    @Autowired
    private GoldenRebindReplayTrigger trigger;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ConfigService configService;

    @MockitoBean
    private GoldenReplayRouteClient routeClient;

    @BeforeEach
    void cleanAndSeedFixtures() {
        deleteFixtures();
        // One family with a released FULL row (must replay) plus a gray row
        // (must not) — status semantics from tp_prompt_template: 1 gray, 2 full.
        jdbcTemplate.update("""
                INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)
                VALUES ('ut-rebind-family', ?, 1, 'released head', 2, 5)
                """, DOMAIN_FULL);
        jdbcTemplate.update("""
                INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)
                VALUES ('ut-rebind-family', ?, 2, 'gray head', 1, 5)
                """, DOMAIN_FULL);
        jdbcTemplate.update("""
                INSERT INTO tp_prompt_template (name, domain, version, content, status, created_by)
                VALUES ('ut-rebind-family', ?, 1, 'released head', 2, 5)
                """, DOMAIN_FAILING);
        calls.clear();
        willAnswer(invocation -> {
            String domain = invocation.getArgument(0);
            calls.add(Map.of("domain", domain,
                    "templateName", invocation.getArgument(1),
                    "controlModel", invocation.getArgument(2),
                    "candidateModel", invocation.getArgument(3),
                    "projectId", invocation.getArgument(4)));
            if (DOMAIN_FAILING.equals(domain)) {
                throw new IllegalStateException("simulated ai-service outage");
            }
            return null;
        }).given(routeClient).runRouteReplay(anyString(), anyString(), anyString(), anyString(), anyLong());
    }

    @AfterEach
    void cleanFixtures() {
        deleteFixtures();
        calls.clear();
    }

    private void deleteFixtures() {
        jdbcTemplate.update("DELETE FROM tp_prompt_template WHERE domain IN (?, ?)",
                DOMAIN_FULL, DOMAIN_FAILING);
    }

    private void stubMapping(String json) {
        when(configService.getValue(anyString(), anyString())).thenReturn(json);
    }

    private boolean awaitCalls(int n) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            if (calls.size() >= n) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    void routeSwitch_dispatchesOneReplayPerReleasedFamily() throws Exception {
        stubMapping("{\"ut-rebind-route\":{\"setName\":\"REBIND-UT-SET\",\"version\":3}}");
        trigger.onProjectRouteChanged(4242L, "route-old", "route-new");
        assertTrue(awaitCalls(1), "expected exactly one dispatch, got " + calls);
        // Let any stray extra dispatch settle, then pin the count.
        Thread.sleep(300);
        assertEquals(1, calls.size(), "gray row must not add a second dispatch");
        Map<String, Object> call = calls.get(0);
        assertEquals(DOMAIN_FULL, call.get("domain"));
        assertEquals("ut-rebind-family", call.get("templateName"));
        assertEquals("route-old", call.get("controlModel"));
        assertEquals("route-new", call.get("candidateModel"));
        assertEquals(4242L, call.get("projectId"));
    }

    @Test
    void identicalRoutes_dispatchNothing() throws Exception {
        stubMapping("{\"ut-rebind-route\":{\"setName\":\"REBIND-UT-SET\",\"version\":3}}");
        trigger.onProjectRouteChanged(4242L, "route-same", "route-same");
        Thread.sleep(600);
        assertEquals(0, calls.size(), "same route on both sides must stay silent");
    }

    @Test
    void unregisteredOrInvalidPack_skipsDomain() throws Exception {
        stubMapping("{\"ut-rebind-route\":{\"setName\":\"\",\"version\":0},"
                + "\"ut-rebind-nopack\":{\"setName\":\"X\",\"version\":1}}");
        trigger.onProjectRouteChanged(4242L, "route-old", "route-new");
        Thread.sleep(600);
        assertEquals(0, calls.size(),
                "empty setName / version<=0 / domain without a released family must all skip");
    }

    @Test
    void failingTarget_doesNotStopFanOutOrThrow() throws Exception {
        stubMapping("{\"ut-rebind-failing\":{\"setName\":\"F\",\"version\":1},"
                + "\"ut-rebind-route\":{\"setName\":\"REBIND-UT-SET\",\"version\":3}}");
        trigger.onProjectRouteChanged(4242L, "route-old", "route-new");
        assertTrue(awaitCalls(2),
                "the healthy domain leg must still dispatch after the failing one threw; got "
                        + calls);
        assertTrue(calls.stream().anyMatch(c -> DOMAIN_FULL.equals(c.get("domain"))),
                "expected the ut-rebind-route leg in " + calls);
    }
}
