package com.sixbean.testpike.mockserver.domain.component;

import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Rule loader pull semantics: pin on drift, keep the last good pin across core
 * outages (serve-last-good), surface staleness honestly (fail-closed report),
 * and stay idle when no routing handle is configured.
 * <p>HTTP is the isolated infrastructure seam here; the real chain (admin ->
 * core snapshot -> loader) is proven at L3 in the follow-up verification pack.
 */
class RuleLoaderTest {

    private static final String CORE_URL = "http://core.test";
    private static final String SNAPSHOT_URL =
            CORE_URL + "/internal/mock/snapshot?spaceKey=demo";

    private final MockRestServiceServer server;
    private final RuleLoader loader;

    RuleLoaderTest() {
        RestClient.Builder builder = RestClient.builder().baseUrl(CORE_URL);
        this.server = MockRestServiceServer.bindTo(builder).build();
        this.loader = new RuleLoader(builder.build(), "demo", 30);
    }

    private void respondWithSnapshot(String version) {
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"msg\":\"ok\",\"data\":{\"version\":\"" + version
                        + "\",\"space\":{\"spaceKey\":\"demo\"}}}", MediaType.APPLICATION_JSON));
    }

    @Test
    void pinsVersionFromFirstSuccessfulSnapshot() {
        respondWithSnapshot("v7");
        loader.refresh();
        assertTrue(loader.everLoaded(), "first pull must hold a pin");
        assertEquals("v7", loader.pinnedVersion());
        assertEquals("demo", loader.pinnedSpaceKey());
        assertFalse(loader.isStale(), "fresh pin is not stale");
        assertEquals(0, loader.consecutiveFailures());
    }

    @Test
    void keepsLastGoodPinAndFlagsStaleWhileCoreUnreachable() {
        respondWithSnapshot("v7");
        loader.refresh();
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withServerError());
        loader.refresh();
        assertEquals("v7", loader.pinnedVersion(), "outage must not drop the last good pin");
        assertEquals(1, loader.consecutiveFailures());
        assertTrue(loader.isStale(), "unconfirmed pin has to surface as stale");
    }

    @Test
    void rePinsOnlyOnContentDrift() {
        respondWithSnapshot("v7");
        loader.refresh();
        server.reset();
        respondWithSnapshot("v8");
        loader.refresh();
        assertEquals("v8", loader.pinnedVersion());
        assertFalse(loader.isStale());
    }

    @Test
    void activeRecordingIdTracksThePinnedSnapshotContent() {
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"version\":\"v9\",\"space\":{\"spaceKey\":\"demo\"},"
                        + "\"activeRecordingId\":42}}", MediaType.APPLICATION_JSON));
        loader.refresh();
        assertEquals(42L, loader.activeRecordingId(),
                "the forwarder slot reads the open cassette box id off the pinned snapshot");
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"version\":\"v10\",\"space\":{\"spaceKey\":\"demo\"}}}",
                MediaType.APPLICATION_JSON));
        loader.refresh();
        assertTrue(loader.activeRecordingId() == null,
                "a re-pinned envelope without the field (sealed box or older core) means open state = null, never a stale id");
    }

    @Test
    void chaosDialTracksThePinnedSnapshotContent() {
        // the dial is traffic content and rides the pin swap —
        // a generation without the field decodes to 0 (tolerant read, older
        // core), an out-of-range value is clamped, never trusted as-is.
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"version\":\"v11\",\"space\":{\"spaceKey\":\"demo\","
                        + "\"chaosPercent\":40}}}", MediaType.APPLICATION_JSON));
        loader.refresh();
        assertEquals(40, loader.chaosPercent(), "the fault kernel reads the dial off the pinned snapshot");
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"version\":\"v12\",\"space\":{\"spaceKey\":\"demo\"}}}",
                MediaType.APPLICATION_JSON));
        loader.refresh();
        assertEquals(0, loader.chaosPercent(),
                "a re-pinned envelope without the field means no injection, never a stale dial");
        server.reset();
        server.expect(requestTo(SNAPSHOT_URL)).andRespond(withSuccess(
                "{\"code\":\"0\",\"data\":{\"version\":\"v13\",\"space\":{\"spaceKey\":\"demo\","
                        + "\"chaosPercent\":250}}}", MediaType.APPLICATION_JSON));
        loader.refresh();
        assertEquals(100, loader.chaosPercent(),
                "an out-of-range dial is clamped to the honest ceiling before the kernel sees it");
    }

    @Test
    void staysIdleWithoutConfiguredSpaceKey() {
        RuleLoader idle = new RuleLoader(
                RestClient.builder().baseUrl(CORE_URL).build(), "", 30);
        idle.refresh();
        assertFalse(idle.everLoaded(), "no routing handle means no pull");
        assertTrue(idle.isStale(), "nothing pinned is by definition stale");
    }
}
