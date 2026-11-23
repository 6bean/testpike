package com.sixbean.testpike.core.mock.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.mq.MockRecordingFrameMessage;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame hold of an open cassette box . Staged
 * frames live in a Redis list ({@code tp_mock_rec_frames:<recordingId>}), NOT
 * in a new PostgreSQL table: the hold is short-lived capture scratch whose
 * lifetime is bounded by the box, and the control-plane row
 * (tp_mock_recording.summary_json) already carries the durable aggregate.
 * <p>TTL is K8-hot ({@code mock.recording.hold-ttl-seconds}) so an abandoned
 * open box can never leak frames forever if the seal call never arrives.
 * The seal path drains the whole list, uploads it as one JSONL cassette via
 * the shared ArtifactStorage, and clears the key.
 */
@Component
public class MockRecordingFrameStore {

    /** K8 hot-update key: keep-alive window of the per-box frame hold. */
    public static final String HOLD_TTL_SECONDS_KEY = "mock.recording.hold-ttl-seconds";

    static final long DEFAULT_HOLD_TTL_SECONDS = 86400L;

    private static final String HOLD_KEY_PREFIX = "tp_mock_rec_frames:";

    private final StringRedisTemplate redis;
    private final SystemConfigReader configReader;
    private final ArtifactStorage artifactStorage;
    private final ObjectMapper objectMapper;

    public MockRecordingFrameStore(StringRedisTemplate redis, SystemConfigReader configReader,
                                   ArtifactStorage artifactStorage, ObjectMapper objectMapper) {
        this.redis = redis;
        this.configReader = configReader;
        this.artifactStorage = artifactStorage;
        this.objectMapper = objectMapper;
    }

    /** Hold key of one box (single source for size/drain/clear paths). */
    public static String holdKey(long recordingId) {
        return HOLD_KEY_PREFIX + recordingId;
    }

    /** Stage frames in append order (right push keeps the wire order for the cassette). */
    public void append(long recordingId, List<MockRecordingFrameMessage> frames) {
        List<String> json = new ArrayList<>(frames.size());
        for (MockRecordingFrameMessage frame : frames) {
            try {
                json.add(objectMapper.writeValueAsString(frame));
            } catch (Exception e) {
                throw new IllegalStateException("frame serialization failed", e);
            }
        }
        String key = holdKey(recordingId);
        redis.opsForList().rightPushAll(key, json);
        redis.expire(key, Duration.ofSeconds(ttlSeconds()));
    }

    /** Frames currently staged (0 when the hold is empty or expired). */
    public long pendingCount(long recordingId) {
        Long n = redis.opsForList().size(holdKey(recordingId));
        return n == null ? 0L : n;
    }

    /** Read the staged frames in capture order (bad lines honestly skipped). */
    public List<MockRecordingFrameMessage> drainAll(long recordingId) {
        List<String> raw = redis.opsForList().range(holdKey(recordingId), 0, -1);
        List<MockRecordingFrameMessage> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String line : raw) {
            try {
                out.add(objectMapper.readValue(line, MockRecordingFrameMessage.class));
            } catch (Exception e) {
                // a corrupt line must not poison the cassette — drop it honestly
                continue;
            }
        }
        return out;
    }

    /** Drop the hold (called after the cassette upload lands). */
    public void clear(long recordingId) {
        redis.delete(holdKey(recordingId));
    }

    /**
     * Upload the sealed cassette as one JSONL object (frame per line, capture
     * order) and return its object key for tp_mock_recording.cassette_ref.
     */
    public String uploadCassette(long recordingId, List<MockRecordingFrameMessage> frames) {
        StringBuilder jsonl = new StringBuilder();
        try {
            for (MockRecordingFrameMessage frame : frames) {
                jsonl.append(objectMapper.writeValueAsString(frame)).append('\n');
            }
        } catch (Exception e) {
            throw new IllegalStateException("cassette serialization failed", e);
        }
        String objectKey = "mock-cassette/rec-" + recordingId + ".jsonl";
        artifactStorage.putBytes(objectKey,
                jsonl.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                "application/x-ndjson");
        return objectKey;
    }

    /** Read a cassette back from object storage (null = key absent). */
    public byte[] readCassetteBytes(String objectKey) {
        return artifactStorage.readBytesQuietly(objectKey);
    }

    private long ttlSeconds() {
        return configReader.getLong(HOLD_TTL_SECONDS_KEY, DEFAULT_HOLD_TTL_SECONDS);
    }
}
