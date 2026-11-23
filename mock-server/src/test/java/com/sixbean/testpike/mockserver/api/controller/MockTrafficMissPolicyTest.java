package com.sixbean.testpike.mockserver.api.controller;

import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.mockserver.domain.component.MockFaultInjector;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockResponseRenderer;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import com.sixbean.testpike.mockserver.domain.component.ScenarioStateStoreInMemory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The miss ladder is a declared space policy now , not only a
 * derivation from "is a cassette box open":
 * <ul>
 * <li>passthrough forwards an unmatched request to the bound target even
 * while nothing records — and stages no cassette frame (nowhere to go),</li>
 * <li>reject answers the plain 404+trace even with an open box and a bound
 * target (the operator said "never touch the real dependency"),</li>
 * <li>llm-fallback (default, also for pins without the field) keeps the
 * derived pre-policy ladder untouched — covered by the other suites.</li>
 * </ul>
 * The MockRestServiceServer here isolates the outbound snapshot leg at L2
 * (RuleLoader precedent); the live chain lands at L3 in the closeout segment.
 */
class MockTrafficMissPolicyTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL = CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    private static String snapshot(String missPolicy, String proxyTargetUrl, Long recordingId) {
        StringBuilder space = new StringBuilder("{\"spaceKey\":\"demo\",\"id\":42,\"projectId\":7");
        if (proxyTargetUrl != null) {
            space.append(",\"proxyTargetUrl\":\"").append(proxyTargetUrl).append('"');
        }
        if (missPolicy != null) {
            space.append(",\"missPolicy\":\"").append(missPolicy).append('"');
        }
        space.append('}');
        String box = recordingId == null ? "" : "\"activeRecordingId\":" + recordingId + ",";
        return """
                {"code":"0","data":{"version":"v1","space":%s,%s
                 "stubs":[
                   {"id":1,"name":"health only","priority":50,"method":"GET",
                    "pathMatchType":"exact","pathPattern":"/health","matchersJson":"[]",
                    "responseJson":"{\\"status\\":202,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ok\\"}"}
                 ]}}
                """.formatted(space, box);
    }

    private HttpServer upstream() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = "{\"real\":true}".getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private record Rig(RuleLoader loader, MockHitRecorder hits,
                       List<com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage> sent) {
    }

    private Rig pinned(String snapshotJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(snapshotJson, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        MockHitRecorder hits = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        List<com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage> sent =
                new ArrayList<>();
        return new Rig(loader, hits, sent);
    }

    private MockTrafficController controller(Rig rig) {
        return new MockTrafficController(rig.loader(), new MockMatchEngine(),
                new MockResponseRenderer(), rig.hits(), new ScenarioStateStoreInMemory(),
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(sent -> rig.sent.add(sent), 1000, 1000),
                new MockFaultInjector(() -> 0.5, 60000), null, 120);
    }

    private MockHttpServletRequest mocked(String method, String path) {
        return new MockHttpServletRequest(method, path);
    }

    @Test
    void pinCarriesTheDeclaredPolicyWithAHonestDefault() {
        Rig explicit = pinned(snapshot("reject", null, null));
        assertEquals("reject", explicit.loader().missPolicy());
        // Older core generations without the field decode to the derived ladder.
        Rig absent = pinned(snapshot(null, null, null));
        assertEquals("llm-fallback", absent.loader().missPolicy());
    }

    @Test
    void passthrough_forwards_without_an_open_box_and_stages_no_frame() throws Exception {
        HttpServer real = upstream();
        try {
            String target = "http://127.0.0.1:" + real.getAddress().getPort();
            Rig rig = pinned(snapshot("passthrough", target, null));
            ResponseEntity<Object> response = controller(rig)
                    .serve(mocked("GET", "/mocks/demo/orders/7"), new MockHttpServletResponse());
            assertEquals(HttpStatus.OK, response.getStatusCode(),
                    "the declared passthrough rung answers from the real dependency");
            assertEquals("true", response.getHeaders()
                    .getFirst(MockTrafficController.FORWARD_HEADER));
            assertEquals(0, rig.sent.size(),
                    "no open box = nowhere for frames to go — none are staged");
        } finally {
            real.stop(0);
        }
    }

    @Test
    void reject_answers_the_plain_trace_even_with_an_open_box_and_bound_target()
            throws Exception {
        HttpServer real = upstream();
        try {
            String target = "http://127.0.0.1:" + real.getAddress().getPort();
            Rig rig = pinned(snapshot("reject", target, 9L));
            ResponseEntity<Object> response = controller(rig)
                    .serve(mocked("GET", "/mocks/demo/orders/7"), new MockHttpServletResponse());
            assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                    "the operator said never forward — the box stays out of the ladder");
            assertNull(response.getHeaders().getFirst(MockTrafficController.FORWARD_HEADER));
            assertEquals(0, rig.sent.size(), "no forwarding, no frames");
            List<MockHitRowMessage> staged = rig.hits().bufferedRows();
            assertEquals(1, staged.size());
            assertEquals(404, staged.get(0).responseStatus());
        } finally {
            real.stop(0);
        }
    }

    @Test
    void stubs_still_win_under_every_policy() throws Exception {
        Rig rig = pinned(snapshot("reject", "http://127.0.0.1:1", null));
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/health"), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(),
                "hit order is untouched: a matched stub answers before any rung runs");
    }
}
