package com.sixbean.testpike.core.appauto.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Component
public class MonitorFrameBuffer {

    private static final Logger log = LoggerFactory.getLogger(MonitorFrameBuffer.class);

    
    public static final String FRAMES_KEY_PREFIX = "appauto-monitor:frames:";
    
    public static final String META_KEY_PREFIX = "appauto-monitor:meta:";

 
    public static final String TTL_KEY = "appauto.monitor.frame-ttl-seconds";
 
    public static final String MAX_KEY = "appauto.monitor.frame-max";
 
    public static final String MAX_BYTES_KEY = "appauto.monitor.frame-max-bytes";

    
    static final long DEFAULT_TTL_SECONDS = 60L;
    static final int DEFAULT_FRAME_MAX = 10;
    static final int DEFAULT_FRAME_MAX_BYTES = 262_144;

    private final StringRedisTemplate redis;
    private final ConfigCacheHolder configCacheHolder;
    private final ObjectMapper objectMapper;

    public MonitorFrameBuffer(StringRedisTemplate redis,
                              ConfigCacheHolder configCacheHolder,
                              ObjectMapper objectMapper) {
        this.redis = redis;
        this.configCacheHolder = configCacheHolder;
        this.objectMapper = objectMapper;
    }

    
    public static String framesKey(long taskId) {
        return FRAMES_KEY_PREFIX + taskId;
    }

    
    public static String metaKey(long taskId) {
        return META_KEY_PREFIX + taskId;
    }

    
    public int ingest(List<Map<String, Object>> frames) {
        if (frames == null || frames.isEmpty()) {
            return 0;
        }
        int maxBytes = frameMaxBytes();
        int accepted = 0;
        long droppedBySize = 0;
        Map<Long, List<String>> byTask = new HashMap<>();
        for (Map<String, Object> frame : frames) {
            Object taskIdObj = frame.get("taskId");
            Object image = frame.get("image");
            if (!(taskIdObj instanceof Number taskIdNum)
                    || !(image instanceof String imageText) || imageText.isBlank()) {
                continue;
            }
            if (imageText.length() > maxBytes) {
                droppedBySize++;
                continue;
            }
            byTask.computeIfAbsent(taskIdNum.longValue(), k -> new ArrayList<>()).add(toJson(frame));
            accepted++;
        }
        int trimmedTotal = 0;
        int cap = frameMax();
        long ttl = frameTtlSeconds();
        for (Map.Entry<Long, List<String>> entry : byTask.entrySet()) {
            long taskId = entry.getKey();
            String key = framesKey(taskId);
            String meta = metaKey(taskId);
            Long lenBefore = redis.opsForList().size(key);
            long before = lenBefore == null ? 0 : lenBefore;
            redis.opsForList().leftPushAll(key, entry.getValue());
            long over = Math.max(0, before + entry.getValue().size() - cap);
            if (over > 0) {
                redis.opsForList().trim(key, 0, cap - 1);
                trimmedTotal += over;
            }
            if (droppedBySize > 0) {
                redis.opsForHash().increment(meta, "dropped", droppedBySize);
            }
            if (over > 0) {
                redis.opsForHash().increment(meta, "trimmed", over);
            }
            Duration ttlDuration = Duration.ofSeconds(ttl);
            redis.expire(key, ttlDuration);
            redis.expire(meta, ttlDuration);
        }
        log.debug("监看帧入账 {} 条（丢 {} /裁 {}）", accepted, droppedBySize, trimmedTotal);
        return accepted;
    }

    
    public Map<String, Object> readFrames(long taskId) {
        List<String> raw = redis.opsForList().range(framesKey(taskId), 0, -1);
        List<Map<String, Object>> frames = new ArrayList<>();
        if (raw != null) {
            for (String json : raw) {
                Map<String, Object> parsed = fromJson(json);
                if (parsed != null) {
                    frames.add(parsed);
                }
            }
        }
        Map<Object, Object> meta = redis.opsForHash().entries(metaKey(taskId));
        Map<String, Object> view = new HashMap<>();
        view.put("frames", frames);
        view.put("dropped", numberOrZero(meta.get("dropped")));
        view.put("trimmed", numberOrZero(meta.get("trimmed")));
        return view;
    }

    private static long numberOrZero(Object v) {
        return v instanceof String s ? Long.parseLong(s) : (v instanceof Number n ? n.longValue() : 0L);
    }

    private String toJson(Map<String, Object> frame) {
        try {
            return objectMapper.writeValueAsString(frame);
        } catch (Exception e) {
            log.warn("监看帧序列化失败，按坏帧丢弃: {}", e.getMessage());
            return "{}";
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fromJson(String json) {
        try {
            return objectMapper.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    
    public long frameTtlSeconds() {
        long v = readLong(TTL_KEY, DEFAULT_TTL_SECONDS);
        return v > 0 ? v : DEFAULT_TTL_SECONDS;
    }

    
    public int frameMax() {
        long v = readLong(MAX_KEY, DEFAULT_FRAME_MAX);
        return v > 0 ? (int) v : DEFAULT_FRAME_MAX;
    }

    
    public int frameMaxBytes() {
        long v = readLong(MAX_BYTES_KEY, DEFAULT_FRAME_MAX_BYTES);
        return v > 0 ? (int) v : DEFAULT_FRAME_MAX_BYTES;
    }

    private long readLong(String key, long fallback) {
        String raw = configCacheHolder.get(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
