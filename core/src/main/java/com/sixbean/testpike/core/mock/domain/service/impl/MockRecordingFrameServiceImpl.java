package com.sixbean.testpike.core.mock.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.MockRecordingFrameBatchMessage;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.mock.domain.component.MockBodyTruncator;
import com.sixbean.testpike.core.mock.domain.component.MockRecordingFrameStore;
import com.sixbean.testpike.core.mock.domain.component.RecordingPathNormalizer;
import com.sixbean.testpike.core.mock.domain.service.MockRecordingFrameService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Frame consumption implementation .
 * <p>Per batch: validate the box exists and is still open (a sealed box
 * rejects late frames — the capture window is a fact of the control plane,
 * not of queue timing), cap both body facets through the shared
 * {@link MockBodyTruncator} gate, stage the frames in the Redis hold, and
 * refresh tp_mock_recording.summary_json with the endpoint cluster.
 * <p>Cluster shape (jsonb): {@code {"groups": {"<METHOD> /a/:id": {"count":
 * n, "samples": [{"frameId":..,"digest":..}]}}, "totalFrames": n}}. Path
 * segments that are pure digits or UUIDs normalize to {@code :id} so an id
 * flood cannot explode the group count, and each group keeps a bounded
 * sample ring (display evidence only — the full frames live in the hold and
 * the sealed cassette).
 */
@Service
public class MockRecordingFrameServiceImpl implements MockRecordingFrameService {

    private static final Logger log = LoggerFactory.getLogger(MockRecordingFrameServiceImpl.class);

    /** Sample ring bound per endpoint group (bounded display, never the full frame list). */
    static final int SAMPLE_RING_LIMIT = 5;

    private final NamedParameterJdbcTemplate jdbc;
    private final MockRecordingFrameStore frameStore;
    private final MockBodyTruncator truncator;
    private final ObjectMapper objectMapper;

    public MockRecordingFrameServiceImpl(NamedParameterJdbcTemplate jdbc,
                                         MockRecordingFrameStore frameStore,
                                         MockBodyTruncator truncator,
                                         ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.frameStore = frameStore;
        this.truncator = truncator;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void consumeBatch(MockRecordingFrameBatchMessage batch) {
        if (batch == null || batch.frames() == null || batch.frames().isEmpty()) {
            return;
        }
        List<MockRecordingFrameMessage> capped = new ArrayList<>(batch.frames().size());
        for (MockRecordingFrameMessage frame : batch.frames()) {
            capped.add(capBodies(frame));
        }
        long recordingId = capped.get(0).recordingId();
        requireOpenBox(recordingId);
        frameStore.append(recordingId, capped);
        refreshSummary(recordingId, capped);
        log.info("Mock 录制帧批次入盒 batch={} box={} frames={}", batch.batchId(), recordingId,
                capped.size());
    }

    /** One truncation gate for both facets — same red line as the hit ledger. */
    private MockRecordingFrameMessage capBodies(MockRecordingFrameMessage frame) {
        return new MockRecordingFrameMessage(frame.frameId(), frame.method(), frame.path(),
                frame.requestHeaders(), truncator.cap(frame.requestBody()),
                frame.responseStatus(), frame.responseHeaders(), truncator.cap(frame.responseBody()),
                frame.latencyMs(), frame.occurredAt(), frame.spaceKey(), frame.recordingId());
    }

    /** Box must exist and still be recording; both facts come from the control-plane row. */
    private void requireOpenBox(long recordingId) {
        List<String> statuses = jdbc.queryForList(
                "SELECT status FROM tp_mock_recording WHERE id = :id",
                new MapSqlParameterSource("id", recordingId), String.class);
        if (statuses.isEmpty()) {
            throw new ServiceException("录制帧指向不存在的录制盒「" + recordingId
                    + "」——整批拒收进死信留痕，禁静默丢", BizErrorCode.NOT_FOUND);
        }
        if (!"recording".equals(statuses.get(0))) {
            throw new ServiceException("录制盒 " + recordingId
                    + " 已封停，迟到帧拒收——封盒即捕获窗口关闭，多余流量不应再入账",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Read-modify-write of the cluster aggregate (one box, single MQ consumer path). */
    private void refreshSummary(long recordingId, List<MockRecordingFrameMessage> frames) {
        String raw = jdbc.queryForObject(
                "SELECT summary_json::text FROM tp_mock_recording WHERE id = :id",
                new MapSqlParameterSource("id", recordingId), String.class);
        ObjectNode summary = parseOrCreate(raw);
        ObjectNode groups = (ObjectNode) summary.get("groups");
        long total = summary.path("totalFrames").asLong(0);
        for (MockRecordingFrameMessage frame : frames) {
            String groupKey = frame.method() + " " + RecordingPathNormalizer.normalize(frame.path());
            ObjectNode group = (ObjectNode) groups.get(groupKey);
            if (group == null) {
                group = groups.putObject(groupKey);
                group.put("count", 0);
                group.putArray("samples");
            }
            group.put("count", group.path("count").asInt(0) + 1);
            appendSample(group, frame);
            total++;
        }
        summary.put("totalFrames", total);
        jdbc.update("UPDATE tp_mock_recording SET summary_json = CAST(:summary AS jsonb),"
                        + " updated_at = now() WHERE id = :id",
                new MapSqlParameterSource()
                        .addValue("summary", writeJson(summary))
                        .addValue("id", recordingId));
    }

    /** Bounded sample ring: keep the newest frames as display evidence of the group. */
    private void appendSample(ObjectNode group, MockRecordingFrameMessage frame) {
        JsonNode samples = group.get("samples");
        if (samples.size() >= SAMPLE_RING_LIMIT) {
            ((com.fasterxml.jackson.databind.node.ArrayNode) samples).remove(0);
        }
        ObjectNode sample = ((com.fasterxml.jackson.databind.node.ArrayNode) samples).objectNode();
        sample.put("frameId", frame.frameId());
        sample.put("digest", digestOf(frame));
        ((com.fasterxml.jackson.databind.node.ArrayNode) samples).add(sample);
    }

    /** One-line human digest of a frame (method path -> status) for the group table. */
    private String digestOf(MockRecordingFrameMessage frame) {
        return frame.method() + " " + frame.path() + " -> "
                + (frame.responseStatus() == null ? "upstream-failure" : frame.responseStatus());
    }

    private ObjectNode parseOrCreate(String raw) {
        if (raw != null && !raw.isBlank()) {
            try {
                JsonNode node = objectMapper.readTree(raw);
                if (node instanceof ObjectNode object && object.has("groups")) {
                    return object;
                }
            } catch (Exception e) {
                log.warn("Mock 录制盒 summary_json 损坏（重建空聚合，计数继续）: {}", e.getMessage());
            }
        }
        ObjectNode fresh = objectMapper.createObjectNode();
        fresh.putObject("groups");
        fresh.put("totalFrames", 0);
        return fresh;
    }

    private String writeJson(ObjectNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException("summary serialization failed", e);
        }
    }
}
