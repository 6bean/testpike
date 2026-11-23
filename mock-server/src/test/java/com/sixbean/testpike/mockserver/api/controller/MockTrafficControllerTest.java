package com.sixbean.testpike.mockserver.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.mockserver.domain.component.MockFaultInjector;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockResponseRenderer;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import com.sixbean.testpike.mockserver.domain.component.ScenarioStateStoreInMemory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Traffic contract of the verdict chain: without a pin the plane fails closed
 * (503); a request that matches a stub is answered by that stub's rendered
 * response (template echo included) with the rule version headers; a request
 * no stub matches gets a 404 carrying the adjudication trace instead of an
 * pretending echo; a stale-but-good pin still serves with the honest stale
 * marker instead of pretending freshness.
 * <p>Fault plane : stub-level fault keys and the space chaos
 * dial ride the hit path only — status/chaos override the served status,
 * disconnect drops the connection without any response, delay holds,
 * malformed corrupts the JSON body, dribble streams it in paced chunks —
 * and every injected fault is named honestly on the ledger row
 * ({@code faultApplied}) instead of pretending to be a real dependency answer.
 */
class MockTrafficControllerTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL =
            CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    /** One space, two rules: a template path with an echo body + a prefix catch-all. */
    private static final String SNAPSHOT_V9 = """
            {"code":"0","data":{"version":"v9","space":{"spaceKey":"demo"},
             "stubs":[
               {"id":1,"name":"order detail","priority":10,"method":"GET",
                "pathMatchType":"template","pathPattern":"/orders/{id}",
                "matchersJson":"[]",
                "responseJson":"{\\"status\\":200,\\"bodyMode\\":\\"template\\",\\"body\\":\\"{\\\\\\"orderId\\\\\\":\\\\\\"{{mock.pathVar.id}}\\\\\\"}\\",\\"headers\\":{\\"X-Demo\\":\\"{{mock.query.page}}\\"}}"},
               {"id":2,"name":"health only","priority":50,"method":"GET",
                "pathMatchType":"exact","pathPattern":"/health","matchersJson":"[]",
                "responseJson":"{\\"status\\":202,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ok\\"}"}
             ]}}
            """;

    private RuleLoader pinnedLoader(String snapshotJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(snapshotJson, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        return loader;
    }

    private MockTrafficController controller(RuleLoader loader) {
        return controller(loader, () -> 0.5);
    }

    private MockTrafficController controller(RuleLoader loader, DoubleSupplier roll) {
        // a generous-capacity recorder with an accepting sink: staging is proven
        // end-to-end here, the batch/degradation semantics belong to MockHitRecorderTest
        MockHitRecorder recorder = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        return new MockTrafficController(loader, new MockMatchEngine(), new MockResponseRenderer(),
                recorder, new ScenarioStateStoreInMemory(),
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(batch -> {
                }, 1000, 1000), new MockFaultInjector(roll, 60000), null, 120);
    }

    /** Controller whose recorder the test can inspect (wiring proof: every served decision stages one row). */
    private MockHitRecorder lastRecorder;

    private MockTrafficController inspectableController(RuleLoader loader) {
        return inspectableController(loader, () -> 0.5);
    }

    private MockTrafficController inspectableController(RuleLoader loader, DoubleSupplier roll) {
        lastRecorder = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        return new MockTrafficController(loader, new MockMatchEngine(),
                new MockResponseRenderer(), lastRecorder, new ScenarioStateStoreInMemory(),
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(batch -> {
                }, 1000, 1000), new MockFaultInjector(roll, 60000), null, 120);
    }

    private MockHttpServletRequest mocked(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        return request;
    }

    @Test
    void refusesTrafficWhileNoRulePinExists() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        RuleLoader neverPinned = new RuleLoader(builder.build(), "demo", 30);
        var response = controller(neverPinned)
                .serve(mocked("GET", "/mocks/demo/orders/42", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "fail-closed: no pin means no mock serving");
        assertEquals("unloaded", response.getHeaders().getFirst(MockTrafficController.RULE_VERSION_HEADER));
        assertFalse((Boolean) ((Map<?, ?>) response.getBody()).get("mocked"));
    }

    @Test
    void servesMatchedStubWithRenderedTemplateAndVersionHeaders() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT_V9));
        var response = controller.serve(mocked("GET", "/mocks/demo/orders/42", "page=1"), new MockHttpServletResponse());
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("v9", response.getHeaders().getFirst(MockTrafficController.RULE_VERSION_HEADER));
        assertEquals("false", response.getHeaders().getFirst(MockTrafficController.RULE_STALE_HEADER));
        assertEquals("order detail", response.getHeaders().getFirst("X-TP-Mock-Rule"));
        assertEquals("1", response.getHeaders().getFirst("X-Demo"));
        assertEquals("{\"orderId\":\"42\"}", response.getBody());
    }

    @Test
    void unmatchedRequestGets404WithTheAdjudicationTrace() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT_V9));
        var response = controller.serve(mocked("POST", "/mocks/demo/orders/42", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "no stub matched — the plane says so instead of echoing");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("mocked"));
        List<?> trace = (List<?>) body.get("matchTrace");
        assertFalse(trace.isEmpty(), "the why-not-matched trail is the answer itself");
    }

    @Test
    void exactRuleServesItsStaticBodyAndStatus() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(SNAPSHOT_V9));
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertEquals("ok", response.getBody());
    }

    @Test
    void stalePinStillServesButMarksTheResponseHonest() throws Exception {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_V9, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withServerError());
        loader.refresh();
        var response = controller(loader).serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), "last good pin keeps serving");
        assertEquals("true", response.getHeaders().getFirst(MockTrafficController.RULE_STALE_HEADER));
        assertTrue(loader.isStale());
    }

    @Test
    void brokenResponsePayloadRefusesInsteadOfPretending() throws Exception {
        String broken = SNAPSHOT_V9.replace("{\\\"status\\\":202,\\\"bodyMode\\\":\\\"static\\\",\\\"body\\\":\\\"ok\\\"}",
                "not-json");
        var response = controller(pinnedLoader(broken))
                .serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode());
        assertFalse((Boolean) ((Map<?, ?>) response.getBody()).get("mocked"));
    }

    /**
     * A stub that already answers with a Set-Cookie header must survive the
     * freshly allocated lane publishing its own Set-Cookie on top: the lane
     * step copies the served headers into a writable container first, so both
     * values stay on the wire in appearance order (stub row, then lane row).
     */
    @Test
    void freshLaneAllocationKeepsStubSetCookieInsteadOfThrowing() throws Exception {
        String withCookieStub = SNAPSHOT_V9.replace(
                "\\\"bodyMode\\\":\\\"static\\\",\\\"body\\\":\\\"ok\\\"",
                "\\\"bodyMode\\\":\\\"static\\\",\\\"body\\\":\\\"ok\\\","
                        + "\\\"headers\\\":{\\\"Set-Cookie\\\":\\\"trail_stub=abc; Path=/\\\"}");
        MockTrafficController controller = controller(pinnedLoader(withCookieStub));
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null),
                new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        List<String> cookies = response.getHeaders().get("Set-Cookie");
        assertEquals(2, cookies.size(),
                "stub row first, lane row second — neither replaces the other");
        assertEquals("trail_stub=abc; Path=/", cookies.get(0));
        String lane = response.getHeaders().getFirst(MockTrafficController.MOCK_SESSION_HEADER);
        assertTrue(lane != null && !lane.isBlank(), "a fresh request gets a published lane");
        assertTrue(cookies.get(1).startsWith("tp_mock_session=" + lane),
                "the published lane cookie names the lane value");
    }

    @Test
    void parseQueryHandlesRepeatedAndEncodedPairs() {
        Map<String, List<String>> query = MockTrafficController.parseQuery("tag=a&tag=b&name=%E8%AE%A2%E5%8D%95");
        assertEquals(List.of("a", "b"), query.get("tag"));
        assertEquals(List.of("订单"), query.get("name"));
    }

    @Test
    void everyServedDecisionStagesOneMaskedLedgerRowHitOrMiss() throws Exception {
        MockTrafficController controller = inspectableController(pinnedLoader(SNAPSHOT_V9));
        MockHttpServletRequest hit = mocked("GET", "/mocks/demo/orders/42", "phone=13812345678");
        hit.addHeader("Authorization", "Bearer leak-me");
        controller.serve(hit, new MockHttpServletResponse());
        controller.serve(mocked("POST", "/mocks/demo/orders/42", null), new MockHttpServletResponse());
        assertEquals(2, lastRecorder.buffered(), "one ledger row per adjudicated request");
        List<com.sixbean.testpike.common.mq.MockHitRowMessage> rows = lastRecorder.bufferedRows();
        assertTrue(rows.get(0).matched(), "template hit recorded as matched with the winner named");
        assertEquals(1L, rows.get(0).stubId());
        assertFalse(rows.get(1).matched(), "miss rows land too — that is how the inspector answers why-not-matched");
        assertEquals("138****5678", rows.get(0).query().get("phone"),
                "the controller path masks before buffering as well");
        assertEquals("***", rows.get(0).headers().get("authorization"));
    }

    // ---- fault plane ------------------------------------------

    /** Response builder document with a JSON body plus an optional fault key. */
    private static String docWithFault(String faultJson) {
        return "{\"status\":202,\"bodyMode\":\"static\",\"body\":\"{\\\"ok\\\":true}\""
                + (faultJson == null ? "" : ",\"fault\":" + faultJson) + "}";
    }

    /** One /health stub carrying the given response doc, plus the space chaos dial. */
    private static String faultSnapshot(String responseDoc, int chaosPercent) {
        try {
            ObjectMapper mapper = new ObjectMapper();
            var root = mapper.createObjectNode();
            root.put("code", "0");
            var data = root.putObject("data");
            data.put("version", "v10");
            var space = data.putObject("space");
            space.put("spaceKey", "demo");
            space.put("chaosPercent", chaosPercent);
            var stub = data.putArray("stubs").addObject();
            stub.put("id", 2);
            stub.put("name", "health only");
            stub.put("priority", 50);
            stub.put("method", "GET");
            stub.put("pathMatchType", "exact");
            stub.put("pathPattern", "/health");
            stub.put("matchersJson", "[]");
            stub.put("responseJson", responseDoc);
            return mapper.writeValueAsString(root);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void statusFaultReplacesServedStatusAndNamesItOnTheLedger() throws Exception {
        MockTrafficController controller = inspectableController(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"status\",\"statusCode\":503}"), 0)));
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "the status fault replaces the stub's own 202");
        assertEquals("status", lastRecorder.bufferedRows().get(0).faultApplied(),
                "attribution lives on the ledger row, not a pretend response header");
    }

    @Test
    void spaceChaosDialInjectsServiceUnavailableWhenRollLandsInside() throws Exception {
        MockTrafficController controller = inspectableController(
                pinnedLoader(faultSnapshot(docWithFault(null), 100)), () -> 0.5);
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, response.getStatusCode(),
                "chaos at 100 claims every served hit");
        assertEquals("chaos", lastRecorder.bufferedRows().get(0).faultApplied());
    }

    @Test
    void chaosDialAtZeroServesNormally() throws Exception {
        var response = controller(pinnedLoader(faultSnapshot(docWithFault(null), 0)))
                .serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), "dial 0 = no injection");
    }

    @Test
    void disconnectFaultAbortsTheResponseWithoutAnyBodyBytes() throws Exception {
        // Wire-level contract L3 caught the old shape): the client
        // must never see a container error page — the response is committed
        // empty, aborted, and the handler returns nothing further to write.
        MockTrafficController controller = inspectableController(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"disconnect\"}"), 0)));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        var returned = controller.serve(mocked("GET", "/mocks/demo/health", null), servletResponse);
        assertNull(returned, "disconnect leaves no ResponseEntity for the view layer to render");
        assertTrue(servletResponse.isCommitted(),
                "the abort is a real wire event: headers flushed before the cut");
        assertEquals(0, servletResponse.getContentAsByteArray().length,
                "not one body byte — the container must not get a chance to paint an error page");
        var row = lastRecorder.bufferedRows().get(0);
        assertEquals("disconnect", row.faultApplied(), "the dropped call is still ledger truth");
        assertNull(row.responseStatus(), "no response plane on a disconnected row");
    }

    @Test
    void delayFaultHoldsBeforeServing() throws Exception {
        MockTrafficController controller = controller(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"delay\",\"minMs\":30,\"maxMs\":30}"), 0)));
        long startedAt = System.currentTimeMillis();
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(), "the delay still serves after holding");
        assertTrue(System.currentTimeMillis() - startedAt >= 25, "the delay fault actually held the response");
    }

    @Test
    void malformedFaultBreaksTheJsonBody() throws Exception {
        MockTrafficController controller = inspectableController(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"malformed\"}"), 0)));
        var response = controller.serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(),
                "malformed keeps the stub status — only the body is corrupted");
        String body = (String) response.getBody();
        assertTrue(body.length() < "{\"ok\":true}".length(), "the body is cut short");
        ObjectMapper mapper = new ObjectMapper();
        assertThrows(Exception.class, () -> mapper.readTree(body),
                "what the client receives is no longer valid JSON — that is the fault");
        assertEquals("malformed", lastRecorder.bufferedRows().get(0).faultApplied(),
                "the corrupted serve is attributed on the ledger row");
    }

    @Test
    void dribbleFaultWritesTheFullBodyInPacedChunksDirectOnTheResponse() throws Exception {
        // Wire-level contract L3 caught the old shape): dribble must
        // reach the socket in paced chunks. Returning a StreamingResponseBody
        // inside ResponseEntity<Object> never worked — the return-value
        // handler only claims ResponseEntity<StreamingResponseBody>, so
        // Jackson serialized the lambda and the client got "{}".
        MockTrafficController controller = controller(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"dribble\",\"chunkBytes\":4,\"chunkDelayMs\":25}"), 0)));
        MockHttpServletResponse servletResponse = new MockHttpServletResponse();
        long startedAt = System.currentTimeMillis();
        var returned = controller.serve(
                mocked("GET", "/mocks/demo/health", null), servletResponse);
        long elapsed = System.currentTimeMillis() - startedAt;
        assertNull(returned, "dribble is written straight to the response — nothing left to render");
        assertEquals(HttpStatus.ACCEPTED.value(), servletResponse.getStatus(),
                "dribble keeps the stub status");
        assertEquals("{\"ok\":true}", servletResponse.getContentAsString(),
                "the paced chunks reassemble to the full intended body");
        assertTrue(elapsed >= 15,
                "the inter-chunk pace reached the wire (elapsed=" + elapsed + "ms for 3 chunks)");
    }

    @Test
    void unknownFaultTypeRefusesWithHonestWordsNotAPretendFault() throws Exception {
        var response = controller(pinnedLoader(faultSnapshot(
                docWithFault("{\"type\":\"teleport\"}"), 0)))
                .serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, response.getStatusCode(),
                "a half-configured fault refuses loudly instead of serving pretending");
        assertFalse((Boolean) ((Map<?, ?>) response.getBody()).get("mocked"));
    }

    @Test
    void normalServesLeaveTheFaultLabelNullOnTheRow() throws Exception {
        MockTrafficController controller = inspectableController(pinnedLoader(SNAPSHOT_V9));
        controller.serve(mocked("GET", "/mocks/demo/orders/42", "page=1"), new MockHttpServletResponse());
        assertNull(lastRecorder.bufferedRows().get(0).faultApplied(),
                "no fault rides a normal response — null is the honest annotation");
    }
}
