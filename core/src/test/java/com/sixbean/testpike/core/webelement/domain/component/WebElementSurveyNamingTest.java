package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.webelement.infrastructure.remote.client.AiServiceElementNamingClient;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Relay-side naming enrichment (WE batch segment P3, core face) — L2 unit.
 * <p>The namer only rewrites the {@code name} field of candidate nodes keyed
 * by anchor identity; ingestion semantics stay single-source in
 * WebElementSurveyCollector (nameOf keeps its role as the honest placeholder
 * when no AI name exists). The client stub is the remote boundary; the real
 * core → ai-service hop is verified on the L3 live face (segment P5).
 * <p>Contract set: ① names travel onto default-tier AND variant-tier nodes
 * sharing one anchor identity, duplicates collapse into one descriptor;
 * ② any client failure / empty result / malformed pool → the ORIGINAL pool
 * string is returned untouched (same reference), and the ledger path keeps
 * its rule-collected placeholder names; ③ pools without locator anchors never
 * burn a remote call.
 */
class WebElementSurveyNamingTest {

    private final AiServiceElementNamingClient client = mock(AiServiceElementNamingClient.class);
    private final WebElementSurveyNaming naming =
            new WebElementSurveyNaming(client, new ObjectMapper());

    private static final String POOL = """
            {"candidates":[
              {"text":"登录","target":{"css":"#login-btn"}},
              {"text":"帮助","target":{"text":"帮助"}},
              {"text":"裸文本无锚"}
            ],
            "variants":[{"viewport":"mobile","candidates":[
              {"text":"登录","target":{"css":"#login-btn"}}
            ]}]}
            """;

    /** ① One identity seen in two tiers = one descriptor, both nodes renamed. */
    @Test
    @SuppressWarnings("unchecked")
    void namesAreRewrittenOntoEveryNodeSharingAnAnchorIdentity() throws Exception {
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    List<Map<String, String>> descriptors = invocation.getArgument(3);
                    assertEquals(2, descriptors.size(), "locator-bearing candidates deduped by identity");
                    return Map.of(descriptors.get(0).get("key"), "登录按钮");
                });

        String rewritten = naming.namePool(7L, "https://demo.example.test/login", 4242L, POOL);

        JsonNode root = new ObjectMapper().readTree(rewritten);
        assertEquals("登录按钮", root.path("candidates").get(0).path("name").asText());
        assertEquals("帮助", root.path("candidates").get(1).path("name").asText(""),
                "un-named candidate keeps its placeholder-free state for nameOf");
        assertEquals("登录按钮", root.path("variants").get(0).path("candidates").get(0)
                .path("name").asText(), "variant-tier twin carries the same AI name");
    }

    /** ② Client blows up → byte-identical original pool, ledger still writes. */
    @Test
    void clientFailureReturnsTheOriginalPool() {
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenThrow(new IllegalStateException("ai-service unreachable"));

        assertSame(POOL, naming.namePool(7L, "https://demo.example.test/login", 4242L, POOL));
    }

    /** ②b Empty name map (switch off on the service side) → original pool. */
    @Test
    void emptyNameMapReturnsTheOriginalPool() {
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList())).thenReturn(Map.of());

        assertSame(POOL, naming.namePool(7L, "https://demo.example.test/login", 4242L, POOL));
    }

    /** R62 segment b2: the session id forwards verbatim to the naming door. */
    @Test
    void sessionIdForwardsToTheNamingDoor() {
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenReturn(Map.of());

        naming.namePool(7L, "https://demo.example.test/login", 4242L, POOL);

        ArgumentCaptor<Long> sessionCaptor = ArgumentCaptor.forClass(Long.class);
        org.mockito.Mockito.verify(client).nameCandidates(anyLong(), anyString(),
                sessionCaptor.capture(), anyList());
        assertEquals(4242L, sessionCaptor.getValue());
    }

    /** ②c Malformed pool → original, no remote call at all. */
    @Test
    void malformedPoolShortCircuitsWithoutRemoteCall() {
        assertSame("{oops", naming.namePool(7L, "https://demo.example.test/login", 4242L, "{oops"));
        verifyNoInteractions(client);
    }

    /** ③ Pool without any locator anchor → zero remote burn. */
    @Test
    void poolWithoutAnchorsNeverCallsTheNamer() {
        String noAnchors = "{\"candidates\":[{\"text\":\"裸文本\"}]}";

        String out = naming.namePool(7L, "https://demo.example.test/login", 4242L, noAnchors);

        assertSame(noAnchors, out);
        verifyNoInteractions(client);
        assertTrue(WebElementSurveyCollector.parse(noAnchors).isEmpty());
    }

    /**
     * ④ Outcome face (whole-site relay, run-ledger naming tally): a naming
     * outage over a real candidate pool reports degraded=true and hands back
     * the ORIGINAL pool (same reference); a pool with nothing to name is an
     * honest not-degraded, never counted as an outage.
     */
    @Test
    void nameOutageIsReportedDegradedOnlyWhenCandidatesWereNamed() {
        org.mockito.Mockito.reset(client);
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenThrow(new IllegalStateException("ai-service unreachable"));

        NamingOutcome outage = naming.namePoolOutcome(
                7L, "https://demo.example.test/login", 4242L, POOL);
        assertTrue(outage.degraded(), "transport outage over named candidates = degraded");
        assertSame(POOL, outage.pool(), "degraded pool is the untouched rule-collected original");

        org.mockito.Mockito.reset(client);
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenReturn(Map.of());
        assertTrue(naming.namePoolOutcome(
                7L, "https://demo.example.test/login", 4242L, POOL).degraded(),
                "empty name map over real candidates = degraded");

        String noAnchors = "{\"candidates\":[{\"text\":\"裸文本\"}]}";
        NamingOutcome idle = naming.namePoolOutcome(
                7L, "https://demo.example.test/login", 4242L, noAnchors);
        assertTrue(!idle.degraded(), "nothing to name is not a supply outage");
        assertSame(noAnchors, idle.pool());

        assertSame("{oops", naming.namePoolOutcome(
                7L, "https://demo.example.test/login", 4242L, "{oops").pool());
    }

    /** ⑤ A successful rewrite reports degraded=false. */
    @Test
    void successfulNamingIsNotDegraded() {
        org.mockito.Mockito.reset(client);
        when(client.nameCandidates(anyLong(), anyString(), anyLong(), anyList()))
                .thenAnswer(invocation -> {
                    List<Map<String, String>> descriptors = invocation.getArgument(3);
                    return Map.of(descriptors.get(0).get("key"), "登录按钮");
                });

        NamingOutcome ok = naming.namePoolOutcome(
                7L, "https://demo.example.test/login", 4242L, POOL);

        assertTrue(!ok.degraded());
        assertTrue(ok.pool() != POOL, "rewritten pool is a fresh string");
        assertTrue(ok.pool().contains("登录按钮"));
    }
}
