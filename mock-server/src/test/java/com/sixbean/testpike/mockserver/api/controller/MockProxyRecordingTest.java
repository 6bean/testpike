package com.sixbean.testpike.mockserver.api.controller;

import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockFaultInjector;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockResponseRenderer;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import com.sixbean.testpike.mockserver.domain.component.ScenarioStateStoreInMemory;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * ii segment B: with an open cassette box and a bound proxy
 * target, a request that NO stub matches rides the wire to the real
 * dependency and comes back answered (never swallowed into a bare 404),
 * while one masked frame is staged for the recording pipeline. Without an
 * open box the plane keeps its old honest 404 verdict untouched. Upstream
 * trouble answers 502 with plain words, and the frame recorder batches with
 * the same honesty rules as the hit ledger (mask-before-buffer, no
 * mixed-box batches, requeue on a failed publish).
 */
class MockProxyRecordingTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL = CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    /** One health rule; space row carries the gated proxy target, top level the box id. */
    private static String snapshot(Long recordingId, String proxyTargetUrl) {
        String space = proxyTargetUrl == null
                ? "{\"spaceKey\":\"demo\"}"
                : "{\"spaceKey\":\"demo\",\"proxyTargetUrl\":\"" + proxyTargetUrl + "\"}";
        String box = recordingId == null ? "" : "\"activeRecordingId\":" + recordingId + ",";
        return """
                {"code":"0","data":{"version":"v9","space":%s,%s
                 "stubs":[
                   {"id":2,"name":"health only","priority":50,"method":"GET",
                    "pathMatchType":"exact","pathPattern":"/health","matchersJson":"[]",
                    "responseJson":"{\\"status\\":202,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ok\\"}"}
                 ]}}
                """.formatted(space, box);
    }

    private RuleLoader pinnedLoader(String snapshotJson) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(snapshotJson, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        return loader;
    }

    private HttpServer upstream(int status, Map<String, String> headers, String body)
            throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            exchange.sendResponseHeaders(status, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        return server;
    }

    private MockHttpServletRequest mocked(String method, String path, String query) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        request.setQueryString(query);
        return request;
    }

    private MockHitRecorder silentHits(RuleLoader loader) {
        return new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
    }

    private MockRecordingFrameRecorder silentFrames() {
        return new MockRecordingFrameRecorder(batch -> {
        }, 1000, 1000);
    }

    private MockTrafficController controller(RuleLoader loader, MockHitRecorder hits,
                                             MockRecordingFrameRecorder frames) {
        ProxyForwarder forwarder =
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5));
        return new MockTrafficController(loader, new MockMatchEngine(), new MockResponseRenderer(),
                hits, new ScenarioStateStoreInMemory(), forwarder, frames,
                new MockFaultInjector(() -> 0.5, 60000), null, 120);
    }

    private static String urlOf(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void forwardsUnmatchedRequestToLiveUpstreamAndStagesOneMaskedFrame() throws IOException {
        HttpServer target = upstream(201, Map.of("X-Upstream", "yes"),
                "{\"phone\":\"13812345678\"}");
        try {
            RuleLoader loader = pinnedLoader(snapshot(7L, urlOf(target)));
            List<MockRecordingFrameBatchMessage> sent = new ArrayList<>();
            MockRecordingFrameRecorder frames =
                    new MockRecordingFrameRecorder(sent::add, 1000, 1000);
            MockHitRecorder hits = silentHits(loader);
            MockTrafficController controller = controller(loader, hits, frames);

            MockHttpServletRequest request = mocked("POST", "/mocks/demo/orders/42", "trace=1");
            request.addHeader("Authorization", "Bearer leak-me");
            request.setContentType("application/json");
            var response = controller.serve(request, new MockHttpServletResponse());

            assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                    "the real dependency answered through the proxy — never a swallowed 404");
            assertEquals("{\"phone\":\"13812345678\"}", response.getBody());
            assertEquals("yes", response.getHeaders().getFirst("X-Upstream"));
            assertEquals("true", response.getHeaders().getFirst("X-TP-Mock-Forwarded"));
            assertEquals("v9", response.getHeaders().getFirst(MockTrafficController.RULE_VERSION_HEADER));
            assertEquals(1, hits.buffered(), "the forwarded miss still lands one ledger row");
            assertFalse(hits.bufferedRows().get(0).matched());

            frames.flush();
            assertEquals(1, sent.size(), "one batch shipped for the staged frame");
            MockRecordingFrameMessage frame = sent.get(0).frames().get(0);
            assertEquals("POST", frame.method());
            assertEquals("/orders/42?trace=1", frame.path());
            assertEquals(7L, frame.recordingId(), "the frame pins the open box id");
            assertEquals("demo", frame.spaceKey());
            assertEquals(201, frame.responseStatus());
            assertEquals("***", frame.requestHeaders().get("authorization"),
                    "frames pass the same PII gate as the ledger before buffering");
            assertTrue(frame.responseBody().contains("138****5678"),
                    "recorded upstream bodies are masked, not raw PII");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void matchedStubStillServesWithoutForwardingEvenWhileRecording() throws IOException {
        HttpServer target = upstream(200, Map.of(), "should-not-see-this");
        try {
            RuleLoader loader = pinnedLoader(snapshot(7L, urlOf(target)));
            List<MockRecordingFrameBatchMessage> sent = new ArrayList<>();
            MockRecordingFrameRecorder frames =
                    new MockRecordingFrameRecorder(sent::add, 1000, 1000);
            var response = controller(loader, silentHits(loader), frames)
                    .serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
            assertEquals(HttpStatus.ACCEPTED, response.getStatusCode(),
                    "match order is untouched: stubs win, the proxy only extends the miss branch");
            assertEquals("ok", response.getBody());
            assertNull(response.getHeaders().getFirst("X-TP-Mock-Forwarded"));
            assertTrue(sent.isEmpty());
            frames.flush();
            assertTrue(sent.isEmpty(), "no frame for a mocked response");
        } finally {
            target.stop(0);
        }
    }

    @Test
    void keepsThePlain404VerdictWhenNoCassetteBoxIsOpen() throws IOException {
        RuleLoader loader = pinnedLoader(snapshot(null, "http://127.0.0.1:1"));
        List<MockRecordingFrameBatchMessage> sent = new ArrayList<>();
        MockRecordingFrameRecorder frames =
                new MockRecordingFrameRecorder(sent::add, 1000, 1000);
        var response = controller(loader, silentHits(loader), frames)
                .serve(mocked("POST", "/mocks/demo/orders/42", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "no open box = the plane never touches the network");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("mocked"));
        frames.flush();
        assertTrue(sent.isEmpty());
    }

    @Test
    void upstreamTroubleAnswers502WithPlainWordsAndStillFramesTheFailure() throws IOException {
        RuleLoader loader = pinnedLoader(snapshot(7L, "http://127.0.0.1:1"));
        List<MockRecordingFrameBatchMessage> sent = new ArrayList<>();
        MockRecordingFrameRecorder frames =
                new MockRecordingFrameRecorder(sent::add, 1000, 1000);
        var response = controller(loader, silentHits(loader), frames)
                .serve(mocked("GET", "/mocks/demo/gone", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.BAD_GATEWAY, response.getStatusCode(),
                "an unreachable bound target is an honest 502, never a pretend mock hit");
        Map<?, ?> body = (Map<?, ?>) response.getBody();
        assertEquals(false, body.get("mocked"));
        assertFalse(String.valueOf(body.get("reason")).isBlank());
        frames.flush();
        assertEquals(1, sent.size());
        assertNull(sent.get(0).frames().get(0).responseStatus(),
                "transport failure records a null status, never a faked code");
    }

    @Test
    void recorderNeverMixesCassetteBoxesInOneBatchAndRequeuesOnPublishFailure() {
        List<MockRecordingFrameBatchMessage> sent = new ArrayList<>();
        MockRecordingFrameRecorder frames = new MockRecordingFrameRecorder(sent::add, 2, 100);
        frames.record(frame("f1", 1L));
        frames.record(frame("f2", 1L));
        frames.record(frame("f3", 2L));
        // one flush ships at most one batch (same posture as MockHitRecorder):
        // the first drain hits the box boundary after two frames, the second
        // flush ships the remaining box-2 frame
        frames.flush();
        frames.flush();
        assertEquals(2, sent.size(), "batch respects the cap AND the box boundary");
        assertEquals(2, sent.get(0).frames().size());
        assertEquals(1, sent.get(0).frames().stream().findAny().orElseThrow().recordingId());
        assertEquals(1, sent.get(1).frames().size());
        assertTrue(sent.stream().allMatch(b -> b.frames().stream()
                .map(MockRecordingFrameMessage::recordingId).distinct().count() == 1),
                "one batch never straddles two cassette boxes");

        MockRecordingFrameRecorder broken = new MockRecordingFrameRecorder(batch -> {
            throw new IllegalStateException("broker down");
        }, 1000, 1000);
        broken.record(frame("f9", 3L));
        broken.flush();
        assertTrue(broken.degraded(), "a failed publish is honest degradation, not silent loss");
        assertEquals(1, broken.buffered(), "failed-batch frames go back to the buffer");
    }

    private static MockRecordingFrameMessage frame(String frameId, long recordingId) {
        return new MockRecordingFrameMessage(frameId, "GET", "/a", Map.of("cookie", "session=x"),
                null, 200, Map.of(), "{}", 5, System.currentTimeMillis(), "demo", recordingId);
    }
}
