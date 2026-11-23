package com.sixbean.testpike.mockserver.api.controller;

import com.sixbean.testpike.mockserver.api.dto.MockLoadStateView;
import com.sixbean.testpike.mockserver.domain.component.MockHitRecorder;
import com.sixbean.testpike.mockserver.domain.component.RuleLoader;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Honest self-check face of the traffic plane (AG design D-b): the management
 * endpoint /load-state must report BOTH faces truthfully — the rule-loader side
 * (which content version is pinned, when it last succeeded, whether it is now
 * stale and why) and the hit-recorder side (buffered rows, degradation, silent
 * loss counter). A reader of this endpoint decides "can I trust what the plane
 * is serving right now", so a pretend-fresh or pretend-empty answer is a bug.
 */
class MockLoadStateControllerTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL =
            CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    private static final String SNAPSHOT_V1 = """
            {"code":"0","data":{"version":"v1","space":{"spaceKey":"demo"},"stubs":[]}}
            """;

    private RuleLoader freshLoader() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_V1, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        return loader;
    }

    private MockHitRecorder quietRecorder() {
        return new MockHitRecorder(batch -> {
        }, () -> "demo", () -> "v1", 1000, 1000);
    }

    @Test
    void reportsFreshPinnedLoaderAndQuietRecorder() {
        RuleLoader loader = freshLoader();
        MockLoadStateView view = new MockLoadStateController(loader, quietRecorder()).loadState();

        assertTrue(view.everLoaded());
        assertEquals("demo", view.pinnedSpaceKey());
        assertEquals("v1", view.pinnedVersion());
        assertFalse(view.stale(), "a pin confirmed on the last poll is honestly fresh");
        assertNotNullLastSuccess(view);
        assertEquals(0, view.consecutiveFailures());
        assertNull(view.lastError());
        assertEquals(0, view.hitBuffered());
        assertFalse(view.hitDegraded());
        assertEquals(0, view.hitOverflowDrops());
    }

    @Test
    void reportsStalePinWithTheFailureReasonAfterCoreBreaks() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(
                withSuccess(SNAPSHOT_V1, MediaType.APPLICATION_JSON));
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);
        loader.refresh();
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withServerError());
        loader.refresh();

        MockLoadStateView view = new MockLoadStateController(loader, quietRecorder()).loadState();
        assertTrue(view.everLoaded(), "the last good pin is still held");
        assertEquals("v1", view.pinnedVersion(), "stale reporting never drops the served version");
        assertTrue(view.stale());
        assertEquals(1, view.consecutiveFailures());
        assertFalse(view.lastError().isBlank(), "the refusal is named, not hidden");
    }

    @Test
    void neverPinnedLoaderReportsUnloadedFailClosed() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        RuleLoader loader = new RuleLoader(builder.build(), "demo", 30);

        MockLoadStateView view = new MockLoadStateController(loader, quietRecorder()).loadState();
        assertFalse(view.everLoaded());
        assertTrue(view.stale(), "no pin = stale by definition (fail-closed)");
        assertNull(view.pinnedVersion());
    }

    @Test
    void surfacesRecorderDegradationAndOverflowCounts() {
        RuleLoader loader = freshLoader();
        MockHitRecorder recorder = new MockHitRecorder(batch -> {
            throw new IllegalStateException("broker down");
        }, loader::pinnedSpaceKey, loader::pinnedVersion, 1, 1);
        recorder.record(new com.sixbean.testpike.common.mq.MockHitRowMessage(
                "GET", "/x", null, null, null, null, null, null, true, 1L, "s", null, 5, null,
                System.currentTimeMillis()));
        recorder.flush();

        MockLoadStateView view = new MockLoadStateController(loader, recorder).loadState();
        assertTrue(view.hitDegraded(), "a failed publish must show up as degraded");
        assertEquals(1, view.hitBuffered(), "failed-batch rows return to the buffer, not to nowhere");
    }

    private static void assertNotNullLastSuccess(MockLoadStateView view) {
        assertTrue(view.lastSuccessAt() != null, "fresh state must carry the confirm timestamp");
    }
}
