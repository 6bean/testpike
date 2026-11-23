package com.sixbean.testpike.mockserver.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mock.MockMatchEngine;
import com.sixbean.testpike.common.mq.MockHitRowMessage;
import com.sixbean.testpike.mockserver.domain.component.LlmFallbackClient;
import com.sixbean.testpike.mockserver.domain.component.MockFaultInjector;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockMissFingerprint;
import com.sixbean.testpike.mockserver.domain.component.MockRecordingFrameRecorder;
import com.sixbean.testpike.mockserver.domain.component.MockResponseRenderer;
import com.sixbean.testpike.mockserver.domain.component.ProxyForwarder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import com.sixbean.testpike.mockserver.domain.component.ScenarioStateStoreInMemory;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * The AI rung of the miss ladder : stubs always win, the
 * proxy rung keeps its place, and ONLY a plain miss may escalate — the data
 * plane asks core's answer door once per miss and serves a paid fallback
 * under {@code X-TP-Mock-Source: llm} so the ledger and the caller can never
 * mistake it for a stub or for a real dependency.
 * <p>Honest degradation (never a 500 dressed up as a fallback): a
 * gate-closed refusal, an unserved envelope and a broken outbound leg all
 * fall through to the ORIGINAL 404+trace semantics, header absent.
 * <p>The MockRestServiceServer here isolates the OUTBOUND HTTP leg at L2
 * (RuleLoader tests set the precedent); the live core↔ai-service chain is
 * verified at L3 (red line 3).
 */
class MockTrafficLlmFallbackTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL = CORE_URL + "/internal/mock/snapshot?spaceKey=demo";
    private static final String ANSWER_URL = CORE_URL + "/internal/mock/llm-fallback/answer";

    /** Space row carries id+projectId; one stub keeps "stubs always win" testable. */
    private static final String SNAPSHOT_V1 = """
            {"code":"0","data":{"version":"v1","space":{"spaceKey":"demo","id":42,"projectId":7},
             "stubs":[
               {"id":1,"name":"health only","priority":50,"method":"GET",
                "pathMatchType":"exact","pathPattern":"/health","matchersJson":"[]",
                "responseJson":"{\\"status\\":202,\\"bodyMode\\":\\"static\\",\\"body\\":\\"ok\\"}"}
             ]}}
            """;

    private final ObjectMapper mapper = new ObjectMapper();

    /** One built client shared by the loader and the answer leg (one server scripts both). */
    private record Rig(RuleLoader loader, RestClient coreClient, MockRestServiceServer server,
                       MockHitRecorder recorder) {
    }

    private Rig pinned() {
        return pinned(null, false);
    }

    /** Answer door scripted BEFORE the pin refresh: MockRestServiceServer forbids adding expectations after traffic. */
    private Rig pinnedWithAnswer(String answerJson) {
        return pinned(answerJson, false);
    }

    private Rig pinnedWithBrokenAnswerLeg() {
        return pinned(null, true);
    }

    private Rig pinned(String answerJson, boolean answerFails) {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder)
                .ignoreExpectOrder(true).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_V1, MediaType.APPLICATION_JSON));
        if (answerJson != null) {
            server.expect(requestTo(ANSWER_URL)).andRespond(
                    withSuccess(answerJson, MediaType.APPLICATION_JSON));
        }
        if (answerFails) {
            server.expect(requestTo(ANSWER_URL)).andRespond(withServerError());
        }
        RestClient client = builder.build();
        RuleLoader loader = new RuleLoader(client, "demo", 30);
        loader.refresh();
        MockHitRecorder recorder = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        return new Rig(loader, client, server, recorder);
    }

    private MockTrafficController controller(Rig rig) {
        return new MockTrafficController(rig.loader(), new MockMatchEngine(),
                new MockResponseRenderer(), rig.recorder(), new ScenarioStateStoreInMemory(),
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(batch -> {
                }, 1000, 1000), new MockFaultInjector(() -> 0.5, 60000),
                new LlmFallbackClient(rig.coreClient()), 120);
    }

    private MockHttpServletRequest mocked(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (body != null) {
            request.setContent(body.getBytes(StandardCharsets.UTF_8));
        }
        return request;
    }

    @Test
    void pinCarriesTheSpaceRowIdentityForTheAnswerDoor() {
        RuleLoader loader = pinned().loader();
        assertEquals(42L, loader.pinnedSpaceId(),
                "the pin must carry the space row id — the answer door is space-scoped");
        assertEquals(7L, loader.pinnedProjectId(),
                "and the owning project id travels for the generation routing anchor");
    }

    @Test
    void servedFallbackRidesTheLlmHeaderAndTheLlmLedgerRow() throws Exception {
        Rig rig = pinnedWithAnswer("""
                        {"code":"0","data":{"served":true,"cacheHit":false,"status":201,
                         "headers":{"X-Gen":"llm"},"body":"{\\"id\\":77}",
                         "tokensSpent":640,"missReason":null,"cacheKey":"GET|/orders/{n}|fp"}}
                        """);
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/orders/7", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.CREATED, response.getStatusCode(),
                "a paid fallback answer serves with its own status");
        assertEquals("llm", response.getHeaders().getFirst(MockTrafficController.MOCK_SOURCE_HEADER),
                "AI-provenanced responses must announce themselves (hard label)");
        assertEquals(77, mapper.readTree((String) response.getBody()).get("id").asInt(),
                "the generated body arrives verbatim");
        List<MockHitRowMessage> staged = rig.recorder().bufferedRows();
        assertEquals(1, staged.size(), "the served decision still stages its ledger row");
        assertEquals("llm", staged.get(0).source(), "same provenance on the ledger");
        assertEquals(201, staged.get(0).responseStatus());
        rig.server().verify();
    }

    @Test
    void answerDoorRequestCarriesSpaceAndNormalizedPathIdentity() throws Exception {
        // The outbound lookup carries the pinned space id and the family-
        // normalized path (/orders/7 -> /orders/{n}) so one paid answer serves
        // the whole endpoint family instead of buying the model per literal id.
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_V1, MediaType.APPLICATION_JSON));
        server.expect(requestTo(ANSWER_URL))
                .andExpect(method(org.springframework.http.HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.allOf(
                        org.hamcrest.Matchers.containsString("\"spaceId\":42"),
                        org.hamcrest.Matchers.containsString("/orders/{n}"))))
                .andRespond(withSuccess("""
                        {"code":"0","data":{"served":false,"cacheHit":false,"status":0,
                         "headers":{},"body":null,"tokensSpent":0,
                         "missReason":"cold","cacheKey":"GET|/orders/{n}|fp"}}
                        """, MediaType.APPLICATION_JSON));
        RestClient client = builder.build();
        RuleLoader loader = new RuleLoader(client, "demo", 30);
        loader.refresh();
        MockHitRecorder recorder = new MockHitRecorder(batch -> {
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1000, 1000);
        MockTrafficController controller = new MockTrafficController(loader, new MockMatchEngine(),
                new MockResponseRenderer(), recorder, new ScenarioStateStoreInMemory(),
                new ProxyForwarder(Duration.ofSeconds(2), Duration.ofSeconds(5)),
                new MockRecordingFrameRecorder(batch -> {
                }, 1000, 1000), new MockFaultInjector(() -> 0.5, 60000),
                new LlmFallbackClient(client), 120);
        controller.serve(mocked("GET", "/mocks/demo/orders/7", null),
                new MockHttpServletResponse());
        server.verify();
    }

    @Test
    void matchedStubNeverConsultsTheFallbackDoor() throws Exception {
        // Hit order red line: stubs always win. No answer-door expectation is
        // registered at all — any escalation call would fail verify() below.
        Rig rig = pinned();
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/health", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.ACCEPTED, response.getStatusCode());
        assertFalse(response.getHeaders().containsKey(MockTrafficController.MOCK_SOURCE_HEADER),
                "a stub answer never claims llm provenance");
        rig.server().verify();
    }

    @Test
    void gateClosedRefusalFallsThroughToTheOriginalNotFoundTrace() throws Exception {
        Rig rig = pinnedWithAnswer("""
                        {"code":"40301","msg":"gate closed","data":null}
                        """);
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/orders/8", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "a closed gate is an honest miss, never a dressed-up answer");
        assertNull(response.getHeaders().getFirst(MockTrafficController.MOCK_SOURCE_HEADER),
                "no llm provenance rides a miss");
        List<MockHitRowMessage> staged = rig.recorder().bufferedRows();
        assertEquals(1, staged.size());
        assertFalse(staged.get(0).matched());
    }

    @Test
    void openGateColdMissEnvelopeFallsThroughToo() throws Exception {
        Rig rig = pinnedWithAnswer("""
                        {"code":"0","data":{"served":false,"cacheHit":false,"status":0,
                         "headers":{},"body":null,"tokensSpent":0,
                         "missReason":"generation leg unavailable","cacheKey":"GET|/orders/{n}|fp"}}
                        """);
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/orders/10", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "an unserved envelope degrades to the original 404+trace");
    }

    @Test
    void brokenOutboundLegNeverBecomesATrafficPlaneError() throws Exception {
        Rig rig = pinnedWithBrokenAnswerLeg();
        ResponseEntity<Object> response = controller(rig)
                .serve(mocked("GET", "/mocks/demo/orders/9", null), new MockHttpServletResponse());
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode(),
                "leg trouble (5xx on core) degrades to the original miss semantics, never a 500");
    }

    @Test
    void fingerprintNormalizesNumericSegmentsAndMasksPiiBeforeDigesting() {
        // segment 2c key discipline: digit runs collapse to {n} so one paid
        // answer serves the whole endpoint family, and the masked digest never
        // carries raw PII (phone-shaped runs star out before hashing).
        MockMissFingerprint fp = new MockMissFingerprint();
        assertEquals(fp.fingerprint("GET", "/orders/12345", null),
                fp.fingerprint("GET", "/orders/999", null),
                "numeric segments normalize onto the same family key");
        String withPii = fp.fingerprint("POST", "/users", "{\"phone\":\"13812345678\"}");
        assertNotNull(withPii, "the fingerprint itself is an opaque digest");
        assertFalse(withPii.contains("13812345678"),
                "a raw PII value must never appear in the digest output");
        assertFalse(fp.fingerprint("GET", "/orders/1", null)
                .equals(fp.fingerprint("POST", "/orders/1", null)),
                "method and path participate in the digest");
    }
}
