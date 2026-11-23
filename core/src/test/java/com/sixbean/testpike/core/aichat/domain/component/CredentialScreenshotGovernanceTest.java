package com.sixbean.testpike.core.aichat.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.storage.StorageProvider;
import com.sixbean.testpike.core.config.domain.component.AiChatKeyRanges;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Credential-screenshot governance contracts : the
 * DevTools Application-panel snapshot is a credential carrier, so the booked
 * original image must be deletable by anchor and the ledger marker must never
 * disturb the existing chatImages booking it lives next to.
 * <p>L2 posture: only infrastructure doors are isolated (storage router,
 * config service, JDBC); every judged behaviour is the component's own.
 */
class CredentialScreenshotGovernanceTest {

    private final ObjectMapper json = new ObjectMapper();
    private final StorageRouter storageRouter = mock(StorageRouter.class);
    private final StorageProvider provider = mock(StorageProvider.class);
    private final ConfigService configService = mock(ConfigService.class);
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final CredentialScreenshotGovernance governance =
            new CredentialScreenshotGovernance(storageRouter, configService, jdbcTemplate);

    private static final String IMAGE_LEDGER_META =
            "{\"chatImages\":[{\"objectKey\":\"chat-images/u1/a.png\",\"fileName\":\"a.png\"}]}";

    @Test
    void credLedgerMarkerIsAnIndependentKeyAndLeavesTheImageLedgerByteIdentical() throws Exception {
        when(storageRouter.systemProvider()).thenReturn(provider);
        LocalDateTime resolvedAt = LocalDateTime.of(2026, 11, 14, 8, 30, 0);

        String merged = governance.withCredLedger(IMAGE_LEDGER_META,
                List.of("chat-images/u1/a.png"), resolvedAt);

        JsonNode root = json.readTree(merged);
        assertEquals(json.readTree(IMAGE_LEDGER_META).path("chatImages"), root.path("chatImages"),
                "the image ledger entries stay untouched — the marker is an independent key");
        JsonNode ledger = root.path("credLedger");
        assertEquals("2026-11-14T08:30", ledger.path("resolvedAt").asText());
        List<String> booked = new java.util.ArrayList<>();
        ledger.path("objectKeys").forEach(n -> booked.add(n.asText()));
        assertEquals(List.of("chat-images/u1/a.png"), booked);
    }

    @Test
    void credentialKeysOfSurvivesGarbageAndBlankInputWithAnEmptyAnswer() {
        assertEquals(List.of(), governance.credentialKeysOf(null));
        assertEquals(List.of(), governance.credentialKeysOf("  "));
        assertEquals(List.of(), governance.credentialKeysOf("not-json{{"));
        assertEquals(List.of(), governance.credentialKeysOf(IMAGE_LEDGER_META),
                "a plain image ledger without the marker is not a credential row");
        assertEquals(List.of("k1", "k2"), governance.credentialKeysOf(
                "{\"credLedger\":{\"objectKeys\":[\"k1\",\"\",\"k2\"]}}"),
                "blank keys are dropped, booked order is kept");
    }

    @Test
    void purgeResolvedDeletesEachBookedKeyOnceThroughTheSingleStorageDoor() {
        when(storageRouter.systemProvider()).thenReturn(provider);
        String meta = "{\"credLedger\":{\"objectKeys\":[\"k1\",\"k2\"]}}";

        int purged = governance.purgeResolved(meta);

        assertEquals(2, purged);
        verify(provider).delete("k1");
        verify(provider).delete("k2");
        assertEquals(0, governance.purgeResolved(IMAGE_LEDGER_META));
        verify(provider, times(2)).delete(anyString());
    }

    @Test
    void sweepPurgesRowsPastTheHotGraceWindowAndFallsBackOnGarbageValues() {
        when(storageRouter.systemProvider()).thenReturn(provider);
        when(configService.getValue(eq(CredentialScreenshotGovernance.KEY_GRACE_MINUTES), anyString()))
                .thenReturn("30");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(),
                any(LocalDateTime.class), anyInt()))
                .thenReturn(List.of("{\"credLedger\":{\"objectKeys\":[\"orphan1\"]}}",
                        "{\"chatImages\":[]}"));
        LocalDateTime now = LocalDateTime.of(2026, 11, 14, 9, 0, 0);

        int purged = governance.sweepPastGrace(now);

        assertEquals(1, purged);
        verify(provider).delete("orphan1");
        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jdbcTemplate).queryForList(anyString(), eq(String.class),
                eq(CredentialScreenshotGovernance.META_CRED_KEY), cutoff.capture(), anyInt());
        assertEquals(now.minusMinutes(30), cutoff.getValue(), "grace is read hot from the K8 key");

        // Garbage value falls back to the shipped default (reader-side fail-closed).
        when(configService.getValue(eq(CredentialScreenshotGovernance.KEY_GRACE_MINUTES), anyString()))
                .thenReturn("abc");
        governance.sweepPastGrace(now);
        ArgumentCaptor<LocalDateTime> fallbackCutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(jdbcTemplate, times(2)).queryForList(anyString(), eq(String.class), anyString(),
                fallbackCutoff.capture(), anyInt());
        assertEquals(now.minusMinutes(CredentialScreenshotGovernance.DEFAULT_GRACE_MINUTES),
                fallbackCutoff.getAllValues().get(1));
    }

    @Test
    void sweepQueryOnlyTouchesRowsBookedWithTheCredentialMarker() {
        when(storageRouter.systemProvider()).thenReturn(provider);
        when(configService.getValue(anyString(), anyString())).thenReturn("10");
        when(jdbcTemplate.queryForList(anyString(), eq(String.class), anyString(),
                any(LocalDateTime.class), anyInt())).thenReturn(List.of());

        governance.sweepPastGrace(LocalDateTime.now());

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate).queryForList(sql.capture(), eq(String.class),
                eq(CredentialScreenshotGovernance.META_CRED_KEY),
                any(LocalDateTime.class), anyInt());
        assertTrue(sql.getValue().contains("jsonb_exists"),
                "the sweep selects credential-marked frames only, marker bound as parameter");
        verify(provider, never()).delete(anyString());
    }

    @Test
    void graceKeyIsIntervalGuardedOnTheWriteSide() {
        assertArrayEquals(new int[]{1, 1440},
                AiChatKeyRanges.rangeOf(CredentialScreenshotGovernance.KEY_GRACE_MINUTES),
                "config page must refuse a grace value outside 1~1440 minutes");
    }
}
