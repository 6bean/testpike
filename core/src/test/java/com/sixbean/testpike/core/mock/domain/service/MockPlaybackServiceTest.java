package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockPlaybackResult;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.api.dto.MockStubInfo;
import com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore;
import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cassette playback plane : turn a box's recorded
 * frames into stub rules through the SINGLE import channel.
 * <p>Spectra under test against the real dev database (no mocks — the same
 * posture as MockRecordingFrameServiceTest):
 * (1) preview on an OPEN box reads the staged frames from the Redis hold —
 * strict mode answers one exact draft per distinct URL (newest frame of
 * the same URL wins), fuzzy mode collapses id segments into one template
 * draft;
 * (2) preview on a SEALED box reads frames back from the uploaded cassette —
 * the hold is gone but the cassette is the durable truth;
 * (3) an unknown recording id and an unknown replay mode are refused in
 * plain words;
 * (4) playback files the drafts through MockStubImportService (normal save
 * chain, validation single point — no second write path) and stamps
 * promoted_count on the box;
 * (5) a second playback of the same box skips rules the space already
 * carries (idempotence inherited from the import channel) and keeps the
 * promoted_count accumulated from the first run;
 * (6) a sealed-empty box (no cassette) plays honestly: zero rules filed,
 * no invented content.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockPlaybackServiceTest {

    private static final String ALLOWLIST_KEY = "mock.proxy.allowlist";

    @Autowired
    private MockPlaybackService playbackService;

    @Autowired
    private MockRecordingService recordingService;

    @Autowired
    private MockRecordingFrameStore frameStore;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private MockStubService stubService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t362p" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** Published space + open cassette box (frames may only stage into a live box). */
    private long[] openSpaceAndBox(String keyPrefix) {
        String key = keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(),
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
        spaceService.transition(spaceId, 1, 0);
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        long boxId = recordingService.start(spaceId, "http://srv.stub.test:8899/orders");
        return new long[] {spaceId, boxId};
    }

    private static MockRecordingFrameMessage frame(long boxId, String path, int status,
                                                   String body, long occurredAt) {
        return new MockRecordingFrameMessage("f-" + UUID.randomUUID(), "GET", path,
                Map.of("accept", "application/json"), null, status,
                Map.of("content-type", "application/json"), body, 12, occurredAt,
                "space.key", boxId);
    }

    /**
     * Escapes stripped for content assertions — the mapper embeds the
     * captured body inside an outer JSON document, so the inner quotes ride
     * backslash-escaped and a raw substring check would trip on them.
     */
    private static String withoutSpaces(String json) {
        return json.replace("\\", "");
    }

    @Test
    void unknownRecordingIsRefusedInPlainWords() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> playbackService.previewDrafts(999_999_999L, "strict"));
        assertTrue(e.getMessage().contains("录制盒"), "got: " + e.getMessage());
    }

    @Test
    void unknownReplayModeIsRefused() {
        long[] pair = openSpaceAndBox("mode");
        try {
            ServiceException e = assertThrows(ServiceException.class,
                    () -> playbackService.previewDrafts(pair[1], "loose"));
            assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void strictPreviewOnOpenBoxMapsStagedFramesNewestPerUrl() {
        long[] pair = openSpaceAndBox("strict");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders?page=1", 200, "{\"v\":1}", 1000L),
                    frame(pair[1], "/orders?page=2", 200, "{\"v\":2}", 2000L)));
            List<MockStubDraft> drafts = playbackService.previewDrafts(pair[1], "strict");
            // Both frames share one bare path → ONE exact draft, newest body wins.
            assertEquals(1, drafts.size());
            MockStubDraft draft = drafts.get(0);
            assertEquals("exact", draft.pathMatchType());
            assertEquals("/orders", draft.pathPattern());
            assertEquals("GET", draft.method());
            assertEquals("record", draft.source());
            assertEquals("recording:" + pair[1], draft.sourceRef());
            assertTrue(withoutSpaces(draft.responseJson()).contains("\"v\":2"),
                    "newest answered frame must represent the URL, got: " + draft.responseJson());
        } finally {
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void fuzzyPreviewCollapsesIdSegmentsIntoTemplate() {
        long[] pair = openSpaceAndBox("fuzzy");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders/1001", 200, "{\"id\":1001}", 1000L),
                    frame(pair[1], "/orders/1002", 500, "{\"error\":\"boom\"}", 2000L)));
            List<MockStubDraft> drafts = playbackService.previewDrafts(pair[1], "fuzzy");
            assertEquals(1, drafts.size());
            MockStubDraft draft = drafts.get(0);
            assertEquals("template", draft.pathMatchType());
            assertEquals("/orders/{id}", draft.pathPattern());
            // The newest frame is a 500 but the group keeps the newest 2xx as
            // its representative (an accidental upstream failure must not
            // become the mock's normal answer).
            assertTrue(withoutSpaces(draft.responseJson()).contains("\"id\":1001"),
                    "newest 2xx must represent the group, got: " + draft.responseJson());
        } finally {
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void previewOnSealedBoxReadsFramesFromCassette() {
        long[] pair = openSpaceAndBox("sealed");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders/42", 200, "{\"id\":42}", 1000L)));
            MockRecordingInfo box = recordingService.stop(pair[1]);
            assertNotNull(box.cassetteRef(), "seal must upload the cassette before this read");
            // The Redis hold is cleared by the seal; preview must fall back
            // to the durable cassette, not answer an invented-empty list.
            List<MockStubDraft> drafts = playbackService.previewDrafts(pair[1], "strict");
            assertEquals(1, drafts.size());
            assertEquals("/orders/42", drafts.get(0).pathPattern());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void playbackFilesRulesThroughSaveChainAndStampsPromotedCount() {
        long[] pair = openSpaceAndBox("play");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders", 200, "{\"items\":[]}", 1000L),
                    frame(pair[1], "/orders/7", 200, "{\"id\":7}", 2000L)));
            MockPlaybackResult result = playbackService.playback(pair[0], pair[1], "strict");
            assertEquals(2, result.createdStubIds().size());
            assertTrue(result.skippedEndpointKeys().isEmpty());
            assertEquals(2, result.promotedCount());

            // Rows live in the space through the NORMAL save chain (validator
            // single point untouched — the ids come back from stubService).
            List<MockStubInfo> rules = stubService.listBySpace(pair[0]);
            assertEquals(2, rules.size());
            assertTrue(rules.stream().allMatch(r -> "record".equals(r.source())),
                    "promoted rules must carry the cassette provenance");

            // Control-plane stamp is durable.
            Integer promoted = jdbcTemplate.queryForObject(
                    "SELECT promoted_count FROM tp_mock_recording WHERE id = ?",
                    Integer.class, pair[1]);
            assertEquals(2, promoted);
        } finally {
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void secondPlaybackSkipsExistingRulesAndKeepsAccumulatedCount() {
        long[] pair = openSpaceAndBox("twice");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders", 200, "{\"items\":[]}", 1000L)));
            playbackService.playback(pair[0], pair[1], "strict");
            MockPlaybackResult again = playbackService.playback(pair[0], pair[1], "strict");
            assertEquals(0, again.createdStubIds().size(), "replay must not twin rules");
            assertEquals(1, again.skippedEndpointKeys().size());
            assertEquals(1, again.promotedCount(), "count accumulates, never resets");
            assertEquals(1, stubService.listBySpace(pair[0]).size());
        } finally {
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    // ---- frame sampling for AI induction ----

    private static final String SAMPLE_MAX_FRAMES_KEY = "mock.ai.summarize.max-sample-frames";

    /** Frames /f/{from}../f/{to} in capture order (append order = oldest first). */
    private static List<MockRecordingFrameMessage> framesRange(long boxId, int from, int to) {
        List<MockRecordingFrameMessage> out = new java.util.ArrayList<>();
        for (int i = from; i <= to; i++) {
            out.add(frame(boxId, "/f/" + i, 200, "{\"i\":" + i + "}", 1000L + i));
        }
        return out;
    }

    @Test
    void sampleFramesOnOpenBoxKeepsTheNewestWithinK8Cap() {
        long[] pair = openSpaceAndBox("smax");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders/1", 200, "{\"id\":1}", 1000L),
                    frame(pair[1], "/orders/2", 200, "{\"id\":2}", 2000L),
                    frame(pair[1], "/orders/3", 200, "{\"id\":3}", 3000L)));
            configCacheHolder.put(SAMPLE_MAX_FRAMES_KEY, "2");
            List<MockRecordingFrameMessage> sample = playbackService.sampleFrames(pair[1]);
            assertEquals(2, sample.size(), "K8 cap must trim the sample to the newest frames");
            assertEquals("/orders/2", sample.get(0).path());
            assertEquals("/orders/3", sample.get(1).path());
        } finally {
            configCacheHolder.remove(SAMPLE_MAX_FRAMES_KEY);
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void sampleFramesWithoutK8RowFallsBackToDefaultOfFifty() {
        long[] pair = openSpaceAndBox("sdef");
        try {
            configCacheHolder.remove(SAMPLE_MAX_FRAMES_KEY);
            frameStore.append(pair[1], framesRange(pair[1], 1, 55));
            List<MockRecordingFrameMessage> sample = playbackService.sampleFrames(pair[1]);
            assertEquals(50, sample.size(), "absent key must default to 50, not 55 or 0");
            assertEquals("/f/6", sample.get(0).path(), "oldest 5 frames trimmed, newest 50 kept");
            assertEquals("/f/55", sample.get(49).path());
        } finally {
            recordingService.stop(pair[1]);
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void sampleFramesOnSealedBoxReadsFromTheCassette() {
        long[] pair = openSpaceAndBox("scas");
        try {
            frameStore.append(pair[1], List.of(
                    frame(pair[1], "/orders/1", 200, "{\"id\":1}", 1000L),
                    frame(pair[1], "/orders/2", 200, "{\"id\":2}", 2000L)));
            recordingService.stop(pair[1]);
            List<MockRecordingFrameMessage> sample = playbackService.sampleFrames(pair[1]);
            assertEquals(2, sample.size(), "sealed box must sample from the durable cassette");
            assertEquals("/orders/1", sample.get(0).path());
            assertEquals("/orders/2", sample.get(1).path());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void sampleFramesOnEmptySealedBoxAnswersEmptyList() {
        long[] pair = openSpaceAndBox("sempty");
        try {
            recordingService.stop(pair[1]);
            assertTrue(playbackService.sampleFrames(pair[1]).isEmpty(),
                    "a box that recorded nothing samples as empty — induction refuses upstream");
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    @Test
    void sampleFramesOfUnknownBoxIsRefusedInPlainWords() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> playbackService.sampleFrames(999_999_998L));
        assertTrue(e.getMessage().contains("录制盒"), "got: " + e.getMessage());
    }

    @Test
    void sealedEmptyBoxPlaysHonestZeroWithoutInventingRules() {
        long[] pair = openSpaceAndBox("empty");
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            MockRecordingInfo box = recordingService.stop(pair[1]);
            assertNull(box.cassetteRef(), "an empty box seals without a cassette file");
            assertTrue(playbackService.previewDrafts(pair[1], "strict").isEmpty());
            MockPlaybackResult result = playbackService.playback(pair[0], pair[1], "strict");
            assertEquals(0, result.createdStubIds().size());
            assertEquals(0, result.promotedCount());
            assertTrue(stubService.listBySpace(pair[0]).isEmpty());
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }
}
