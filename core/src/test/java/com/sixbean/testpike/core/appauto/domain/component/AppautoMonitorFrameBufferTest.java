package com.sixbean.testpike.core.appauto.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoMonitorFrameBufferTest {

    @Autowired
    private MonitorFrameBuffer monitorFrameBuffer;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    
    private static final long UNIQUE_TASK_BASE = 987_600_000L;

    private long uniqueTaskId() {
        return UNIQUE_TASK_BASE + System.nanoTime() % 100_000;
    }

    private static Map<String, Object> frame(long taskId, int stepNo, String image, Boolean sensitive) {
        Map<String, Object> f = new HashMap<>();
        f.put("taskId", taskId);
        f.put("stepNo", stepNo);
        f.put("image", image);
        if (sensitive != null) {
            f.put("sensitive", sensitive);
        }
        return f;
    }

    @Test
    void v153监控K8键seed在位() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT config_key, config_value FROM tp_sys_config
                WHERE config_key IN ('appauto.monitor.frame-ttl-seconds',
                                     'appauto.monitor.frame-max',
                                     'appauto.monitor.frame-max-bytes')
                """);
        assertEquals(3, rows.size(), "V153 三枚 K8 键必须落库（TTL 禁硬编码全局口径）");
        for (Map<String, Object> row : rows) {
            assertNotNull(row.get("config_value"));
        }
    }

    @Test
    void 帧写入后按环形上限裁剪且最新在前() {
        long taskId = uniqueTaskId();
        int cap = monitorFrameBuffer.frameMax();
        try {
            List<Map<String, Object>> frames = new ArrayList<>();
            for (int i = 0; i < cap + 5; i++) {
                frames.add(frame(taskId, i, "aGVsbG8=" + i, null));
            }
            int accepted = monitorFrameBuffer.ingest(frames);
            assertEquals(cap + 5, accepted);

            Map<String, Object> view = monitorFrameBuffer.readFrames(taskId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) view.get("frames");
            assertEquals(cap, stored.size(), "环形=至多 frame-max 帧");
            assertEquals(cap + 4, ((Number) stored.get(0).get("stepNo")).intValue());
            assertEquals(5, ((Number) view.get("trimmed")).intValue(), "丢旧帧计数=超出条数");
        } finally {
            clear(taskId);
        }
    }

    @Test
    void 帧键TTL落在K8配置值域内() {
        long taskId = uniqueTaskId();
        try {
            monitorFrameBuffer.ingest(List.of(frame(taskId, 1, "aGk=", null)));
            Long ttl = redis.getExpire(MonitorFrameBuffer.framesKey(taskId), TimeUnit.SECONDS);
            assertNotNull(ttl);
            assertTrue(ttl > 0 && ttl <= monitorFrameBuffer.frameTtlSeconds(),
                    "写后 TTL 必须随 K8 键在位，实测=" + ttl);
        } finally {
            clear(taskId);
        }
    }

    @Test
    void 超限帧诚实丢弃并计数透出() {
        long taskId = uniqueTaskId();
        try {
            String oversized = "x".repeat(monitorFrameBuffer.frameMaxBytes() + 10);
            int accepted = monitorFrameBuffer.ingest(List.of(
                    frame(taskId, 1, oversized, null),
                    frame(taskId, 2, "aGk=", null)));
            assertEquals(1, accepted, "超限帧不计入受理数");
            Map<String, Object> view = monitorFrameBuffer.readFrames(taskId);
            assertEquals(1, ((Number) view.get("dropped")).intValue(),
                    "dropped 计数必须透出禁静默");
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) view.get("frames");
            assertEquals(1, stored.size());
            assertEquals(2, ((Number) stored.get(0).get("stepNo")).intValue());
        } finally {
            clear(taskId);
        }
    }

    @Test
    void sensitive旗标透传零丢失() {
        long taskId = uniqueTaskId();
        try {
            monitorFrameBuffer.ingest(List.of(frame(taskId, 3, "aGk=", true)));
            Map<String, Object> view = monitorFrameBuffer.readFrames(taskId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) view.get("frames");
            assertEquals(1, stored.size());
            assertEquals(Boolean.TRUE, stored.get(0).get("sensitive"),
                    "G4 敏感旗标=T304 通道透传面，前端暂停显示由包③消费");
        } finally {
            clear(taskId);
        }
    }

    @Test
    void 坏帧诚实跳过不炸主链() {
        long taskId = uniqueTaskId();
        Map<String, Object> noTask = new HashMap<>();
        noTask.put("image", "aGk=");
        Map<String, Object> noImage = frame(taskId, 9, null, null);
        try {
            int accepted = monitorFrameBuffer.ingest(List.of(noTask, noImage));
            assertEquals(0, accepted, "缺 taskId/缺 image 的坏帧=诚实跳过");
            Map<String, Object> view = monitorFrameBuffer.readFrames(taskId);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> stored = (List<Map<String, Object>>) view.get("frames");
            assertTrue(stored.isEmpty());
            assertFalse(view.containsKey("error"), "读口零异常语义=空面诚实呈现");
        } finally {
            clear(taskId);
        }
    }

    private void clear(long taskId) {
        redis.delete(MonitorFrameBuffer.framesKey(taskId));
        redis.delete(MonitorFrameBuffer.metaKey(taskId));
    }
}
