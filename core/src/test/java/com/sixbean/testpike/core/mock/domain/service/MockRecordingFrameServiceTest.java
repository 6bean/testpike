package com.sixbean.testpike.core.mock.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore;
import com.sixbean.testpike.core.mock.infrastructure.mq.MockRecordingFrameConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Recording frame consumption plane : core consumes
 * {@code mock.recording.frame} batches, stages frames in the Redis hold, and
 * keeps tp_mock_recording.summary_json as a live endpoint-cluster aggregate.
 * <p>Spectra under test against the real dev database (no mocks):
 * (1) two frames of the same endpoint cluster into one group with both frame
 * ids in the sample ring; numeric and UUID path segments normalize to the
 * {@code :id} placeholder so id-spam cannot explode the cluster count;
 * (2) distinct endpoints form distinct groups;
 * (3) a redelivered batch (same batchId) is skipped exactly once behind the
 * consumer double guard;
 * (4) an unknown recording id rejects the batch upward so the message lands
 * in the dead letter visibly — silent loss is the forbidden alternative;
 * (5) frames arriving after the box was sealed are rejected (the capture
 * window is closed, the backlog must stay reviewable);
 * (6) sealing uploads the staged frames as one JSONL cassette to object
 * storage, stamps cassette_ref, and clears the Redis hold.
 */
@SpringBootTest(classes = CoreApplication.class)
class MockRecordingFrameServiceTest {

    private static final String ALLOWLIST_KEY = "mock.proxy.allowlist";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private MockRecordingFrameService frameService;

    @Autowired
    private MockRecordingFrameConsumer frameConsumer;

    @Autowired
    private MockRecordingFrameStore frameStore;

    @Autowired
    private MockRecordingService recordingService;

    @Autowired
    private MockSpaceService spaceService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long newProject() {
        String code = "t362f" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, code, code);
    }

    /** Published space + open box (the only state that may accept frames). */
    private long[] openSpaceAndBox(String keyPrefix) {
        String key = keyPrefix + "-" + UUID.randomUUID().toString().substring(0, 10);
        long spaceId = spaceService.create(newProject(),
                new MockSpaceInput("Space " + key, key, null, null,
                        "prefix", "none", null, null, 100, null));
        spaceService.transition(spaceId, 1, 0);
        configCacheHolder.put(ALLOWLIST_KEY, "*.stub.test");
        try {
            long boxId = recordingService.start(spaceId, "http://srv.stub.test/orders");
            return new long[]{spaceId, boxId};
        } finally {
            configCacheHolder.remove(ALLOWLIST_KEY);
        }
    }

    private MockRecordingFrameMessage frame(long recordingId, String spaceKey, String frameId,
                                            String method, String path, Integer status) {
        return new MockRecordingFrameMessage(frameId, method, path,
                Map.of("x-trace", "t"), "{\"a\":1}",
                status, Map.of("content-type", "application/json"), "{\"ok\":true}",
                12, 1_700_000_000_000L, spaceKey, recordingId);
    }

    private MockRecordingFrameBatchMessage batch(List<MockRecordingFrameMessage> frames) {
        return new MockRecordingFrameBatchMessage("fb-" + UUID.randomUUID(), frames,
                System.currentTimeMillis());
    }

    private String spaceKeyOf(long spaceId) {
        return jdbcTemplate.queryForObject(
                "SELECT space_key FROM tp_mock_space WHERE id = ?", String.class, spaceId);
    }

    private JsonNode summaryOf(long boxId) {
        String raw = jdbcTemplate.queryForObject(
                "SELECT summary_json::text FROM tp_mock_recording WHERE id = ?",
                String.class, boxId);
        try {
            return MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new AssertionError("summary_json must always be valid JSON, got: " + raw, e);
        }
    }

    @Test
    void sameEndpointFramesClusterIntoOneGroupWithSampleRing() {
        long[] pair = openSpaceAndBox("clus");
        String spaceKey = spaceKeyOf(pair[0]);
        frameService.consumeBatch(batch(List.of(
                frame(pair[1], spaceKey, "f-1", "POST", "/orders/1", 200),
                frame(pair[1], spaceKey, "f-2", "POST", "/orders/2", 201))));

        JsonNode summary = summaryOf(pair[1]);
        JsonNode group = summary.get("groups").get("POST /orders/:id");
        assertNotNull(group, "numeric segments must normalize to :id so both frames cluster");
        assertEquals(2, group.get("count").asInt());
        assertEquals(2, group.get("samples").size(), "sample ring keeps both frame digests");
        assertEquals("f-1", group.get("samples").get(0).get("frameId").asText());
        assertEquals("f-2", group.get("samples").get(1).get("frameId").asText());
        assertEquals(2, summary.get("totalFrames").asInt());
    }

    @Test
    void uuidPathSegmentsNormalizeToIdPlaceholder() {
        long[] pair = openSpaceAndBox("uuid");
        String spaceKey = spaceKeyOf(pair[0]);
        frameService.consumeBatch(batch(List.of(
                frame(pair[1], spaceKey, "u-1", "GET", "/orders/550e8400-e29b-41d4-a716-446655440000", 200),
                frame(pair[1], spaceKey, "u-2", "GET", "/orders/42", 200))));

        JsonNode group = summaryOf(pair[1]).get("groups").get("GET /orders/:id");
        assertNotNull(group, "UUID and numeric segments share the :id normalization");
        assertEquals(2, group.get("count").asInt());
    }

    @Test
    void distinctEndpointsFormDistinctGroups() {
        long[] pair = openSpaceAndBox("two");
        String spaceKey = spaceKeyOf(pair[0]);
        frameService.consumeBatch(batch(List.of(
                frame(pair[1], spaceKey, "d-1", "POST", "/orders", 200),
                frame(pair[1], spaceKey, "d-2", "GET", "/health", 204))));

        JsonNode groups = summaryOf(pair[1]).get("groups");
        assertEquals(2, groups.size(), "one group per distinct method+normalized-path pair");
        assertEquals(1, groups.get("POST /orders").get("count").asInt());
        assertEquals(1, groups.get("GET /health").get("count").asInt());
    }

    @Test
    void redeliveredBatchIsSkippedExactlyOnce() {
        long[] pair = openSpaceAndBox("dedup");
        String spaceKey = spaceKeyOf(pair[0]);
        MockRecordingFrameBatchMessage once = batch(List.of(
                frame(pair[1], spaceKey, "r-1", "GET", "/ping", 200)));
        frameConsumer.onMessage(once);
        frameConsumer.onMessage(once); // requeue/replay of the same batchId

        assertEquals(1, summaryOf(pair[1]).get("totalFrames").asInt(),
                "idempotent double guard must drop the replay, never double-count");
    }

    @Test
    void unknownRecordingRejectsBatchIntoVisibleDeadLetter() {
        long[] pair = openSpaceAndBox("ghost");
        String spaceKey = spaceKeyOf(pair[0]);
        ServiceException ex = assertThrows(ServiceException.class, () -> frameService.consumeBatch(
                batch(List.of(frame(999_999_999L, spaceKey, "g-1", "GET", "/x", 200)))));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("999999999"), "rejection names the unknown box");
        assertEquals(0, frameStore.pendingCount(999_999_999L),
                "a rejected batch must stage nothing");
    }

    @Test
    void sealedBoxRejectsLateFrames() {
        long[] pair = openSpaceAndBox("late");
        String spaceKey = spaceKeyOf(pair[0]);
        recordingService.stop(pair[1]);
        ServiceException ex = assertThrows(ServiceException.class, () -> frameService.consumeBatch(
                batch(List.of(frame(pair[1], spaceKey, "l-1", "GET", "/x", 200)))));
        assertTrue(ex.getMessage().contains("已封") || ex.getMessage().contains("封停"),
                "late-frame rejection must say the box is sealed, got: " + ex.getMessage());
    }

    @Test
    void sealUploadsCassetteJsonlAndClearsStaging() throws Exception {
        long[] pair = openSpaceAndBox("cass");
        String spaceKey = spaceKeyOf(pair[0]);
        frameService.consumeBatch(batch(List.of(
                frame(pair[1], spaceKey, "c-1", "POST", "/orders/1", 200),
                frame(pair[1], spaceKey, "c-2", "POST", "/orders/2", 201))));
        assertEquals(2, frameStore.pendingCount(pair[1]), "frames stage in the Redis hold while open");

        MockRecordingInfo box = recordingService.stop(pair[1]);
        assertNotNull(box.cassetteRef(), "seal must stamp the uploaded cassette key");
        assertEquals(0, frameStore.pendingCount(pair[1]), "seal must clear the Redis hold");

        byte[] raw = frameStore.readCassetteBytes(box.cassetteRef());
        assertNotNull(raw, "cassette must be readable back from object storage");
        String jsonl = new String(raw, StandardCharsets.UTF_8);
        String[] lines = jsonl.split("\n");
        assertEquals(2, lines.length, "one JSONL line per staged frame");
        assertEquals("c-1", MAPPER.readTree(lines[0]).get("frameId").asText());
        assertEquals("c-2", MAPPER.readTree(lines[1]).get("frameId").asText());
    }

    @Test
    void stopWithoutFramesKeepsCassetteRefNull() {
        long[] pair = openSpaceAndBox("noframes");
        MockRecordingInfo box = recordingService.stop(pair[1]);
        assertNull(box.cassetteRef(), "an empty box seals honestly with no cassette, not a stub file");
    }
}
