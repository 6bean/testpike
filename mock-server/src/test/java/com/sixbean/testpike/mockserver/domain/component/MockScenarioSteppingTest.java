package com.sixbean.testpike.mockserver.domain.component;

import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.mockserver.api.controller.MockTrafficController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The stateful stepping contract : scenario stubs fire only
 * from their required state, a 2xx winner advances the lane, 5xx and misses
 * never do, session lanes never cross (CI parallelism red line), the global
 * scope shares exactly one lane, OPTIONS answers without consuming a step,
 * and a dead state store fails closed — stateless rules keep serving,
 * scenario traffic is refused with plain words instead of pretending states.
 */
class MockScenarioSteppingTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL = CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    /** checkout machine (session lanes) + flagboard (global) + stateless health. */
    private static final String SNAPSHOT = """
            {"code":"0","data":{"version":"v3","space":{"spaceKey":"demo"},
             "scenarios":[
               {"id":9,"name":"checkout","definitionJson":"{\\"states\\":[\\"STARTED\\",\\"PAID\\"]}"}
             ],
             "stubs":[
               {"id":1,"name":"start order","priority":10,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/orders","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"started\\"}",
                "scenarioName":"checkout","requiredState":null,"newState":"STARTED"},
               {"id":2,"name":"pay","priority":20,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/pay","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"paid\\"}",
                "scenarioName":"checkout","requiredState":"STARTED","newState":"PAID"},
               {"id":3,"name":"paid page","priority":30,"method":"GET",
                "pathMatchType":"exact","pathPattern":"/paid","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"receipt\\"}",
                "scenarioName":"checkout","requiredState":"PAID","newState":null},
               {"id":4,"name":"broken pay","priority":40,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/pay500","matchersJson":"[]",
                "responseJson":"{\\"status\\":500,\\"bodyMode\\":\\"static\\",\\"body\\":\\"boom\\"}",
                "scenarioName":"checkout","requiredState":"STARTED","newState":"PAID"},
               {"id":5,"name":"ghost advance","priority":50,"method":"POST",
                "pathMatchType":"exact","pathPattern":"/ghost","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ghost\\"}",
                "scenarioName":"checkout","requiredState":null,"newState":"GHOST"},
               {"id":6,"name":"health","priority":60,"method":"GET",
                "pathMatchType":"exact","pathPattern":"/health","matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ok\\"}"}
             ]}}
            """;

    private ScenarioStateStoreInMemory store = new ScenarioStateStoreInMemory();
    private MockHitRecorder lastRecorder;

    /** Pin the snapshot into a loader (the load chain itself is proven in its own test). */
    private RuleLoader pinnedLoader(String snapshotJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(snapshotJson, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        return loader;
    }

    private MockTrafficController controller(RuleLoader loader, MockScenarioStateStore stateStore) {
        lastRecorder = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        return new MockTrafficController(loader, new MockMatchEngine(),
                new MockResponseRenderer(), lastRecorder, stateStore,
                new ProxyForwarder(java.time.Duration.ofSeconds(2), java.time.Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(batch -> {
                }, 1000, 1000), new MockFaultInjector(() -> 0.5, 60000), null, 120);
    }

    private MockHttpServletRequest mocked(String method, String path, String lane) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/mocks/demo" + path);
        if (lane != null) {
            request.addHeader("X-TP-Mock-Session", lane);
        }
        return request;
    }

    @Test
    void scenarioStubRefusesToFireFromTheWrongStateWithAHonestTrace() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        var response = controller.serve(mocked("GET", "/paid", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "fresh lane sits in the initial state — the PAID rule cannot fire yet");
        List<?> trace = (List<?>) ((Map<?, ?>) response.getBody()).get("matchTrace");
        assertTrue(trace.stream().anyMatch(t -> String.valueOf(t).contains("state")),
                "the trace names the state pre-filter as the reason of the miss");
    }

    @Test
    void advancingChainWalksTheMachineAndEchoesTheState() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        var start = controller.serve(mocked("POST", "/orders", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, start.getStatusCode());
        var pay = controller.serve(mocked("POST", "/pay", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, pay.getStatusCode(), "STARTED advances after the first rule fired");
        assertEquals("PAID", pay.getHeaders().getFirst(MockTrafficController.MOCK_STATE_HEADER));
        var receipt = controller.serve(mocked("GET", "/paid", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, receipt.getStatusCode());
        assertEquals("demo|checkout|session|lane-a", store.lastKey,
                "state key = space|scenario|scope|lane — lanes are first-class in the key");
    }

    @Test
    void sessionLanesNeverCross() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        controller.serve(mocked("POST", "/orders", "lane-a"), new MockHttpServletResponse());
        controller.serve(mocked("POST", "/pay", "lane-a"), new MockHttpServletResponse());
        var otherLane = controller.serve(mocked("GET", "/paid", "lane-b"), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, otherLane.getStatusCode(),
                "lane A being PAID must not lift lane B — CI parallel journeys depend on this");
    }

    @Test
    void failureAndMissesNeverAdvanceTheLane() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        controller.serve(mocked("POST", "/orders", "lane-a"), new MockHttpServletResponse());
        var failing = controller.serve(mocked("POST", "/pay500", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, failing.getStatusCode());
        var pay = controller.serve(mocked("POST", "/pay", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, pay.getStatusCode(),
                "the 500 rule must NOT have consumed the STARTED→PAID step");
    }

    @Test
    void undeclaredAdvanceIsRefusedWithoutCorruptingTheLane() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        var ghost = controller.serve(mocked("POST", "/ghost", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, ghost.getStatusCode(), "the rule still answers its request");
        assertTrue(store.values.isEmpty(),
                "GHOST is undeclared — the advance is refused, never written to the store");
    }

    @Test
    void optionsPreflightAnswersWithoutEnteringTheKernelOrStepping() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        var preflight = controller.serve(mocked("OPTIONS", "/pay", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.NO_CONTENT, preflight.getStatusCode());
        assertTrue(store.values.isEmpty(), "OPTIONS must not consume a scenario step");
    }

    @Test
    void deadStoreDegradesGracefullyForStatelessAndFailsClosedForScenarioTraffic() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                new ScenarioStateStoreBroken());
        var health = controller.serve(mocked("GET", "/health", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, health.getStatusCode(),
                "stateless rules are untouched by a dead state store");
        var scenario = controller.serve(mocked("POST", "/pay", "lane-a"), new MockHttpServletResponse());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, scenario.getStatusCode(),
                "scenario-only traffic fails closed instead of pretending states");
        Map<?, ?> body = (Map<?, ?>) scenario.getBody();
        assertFalse((Boolean) body.get("mocked"));
        assertTrue(String.valueOf(body.get("reason")).contains("仿真态存储不可用"),
                "the refusal is in plain words, not a stack trace");
    }

    @Test
    void anonymousTrafficGetsASetCookieLaneAndReusesIt() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/mocks/demo/orders");
        var first = controller.serve(anonymous, new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, first.getStatusCode());
        String echoed = first.getHeaders().getFirst(MockTrafficController.MOCK_SESSION_HEADER);
        assertNotNull(echoed, "the allocated lane is echoed on the response");
        var setCookie = (List<String>) first.getHeaders().get("Set-Cookie");
        assertNotNull(setCookie, "browser callers get the lane as a cookie (C-5 ruling)");
        assertTrue(setCookie.get(0).contains("tp_mock_session=" + echoed));
        assertTrue(setCookie.get(0).contains("SameSite=Lax"));

        MockHttpServletRequest followed = new MockHttpServletRequest("POST", "/mocks/demo/pay");
        followed.setCookies(new jakarta.servlet.http.Cookie("tp_mock_session", echoed));
        var second = controller.serve(followed, new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, second.getStatusCode(),
                "the cookie lane continues the same journey — zero-setup browser flows");
        assertEquals(echoed, second.getHeaders().getFirst(MockTrafficController.MOCK_SESSION_HEADER));
    }

    @Test
    void ledgerRowsCarryTheResolvedLaneNotTheRawHeader() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        MockHttpServletRequest anonymous = new MockHttpServletRequest("POST", "/mocks/demo/orders");
        var first = controller.serve(anonymous, new MockHttpServletResponse());
        String lane = first.getHeaders().getFirst(MockTrafficController.MOCK_SESSION_HEADER);
        List<com.sixbean.testpike.common.mq.MockHitRowMessage> rows = lastRecorder.bufferedRows();
        assertEquals(1, rows.size());
        assertEquals(lane, rows.get(0).sessionId(),
                "the inspector filters by the lane the traffic actually walked");
    }

    @Test
    void ttlFromConfigurationBoundsTheIdleLaneLifetime() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT),
                store);
        controller.serve(mocked("POST", "/orders", "lane-a"), new MockHttpServletResponse());
        assertEquals(120, store.lastTtl.toMinutes(),
                "idle lanes expire on the mock.scenario.ttl-minutes threshold, never hardcoded");
    }
}
