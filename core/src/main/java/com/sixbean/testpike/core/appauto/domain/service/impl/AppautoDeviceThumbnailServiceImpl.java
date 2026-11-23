package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.core.appauto.domain.component.AppautoConfigReader;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceThumbnailService;
import com.sixbean.testpike.core.executor.domain.component.AppAutoDispatchSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Device-wall thumbnail assembly (see {@link AppautoDeviceThumbnailService}).
 * <p>Chain per call: device row (H1 anchor) → short-cache hit → running-task
 * lookup attributed by the dispatch envelope ({@link
 * AppAutoDispatchSupport#leaseDeviceId}, the single addressing source — never
 * by serial guessing) → newest buffered monitor frame from the real ring →
 * bilinear downscale keeping the aspect ratio → JPEG base64 → cached under the
 * device for the hot-key reuse window. Non-live cells (IDLE / NO_FRAME) are
 * honest views, not errors, and are never cached so a starting feed shows up
 * on the next poll.
 * <p>Storage base line = Redis short cache, no new table (the frame channel's
 * own 10-01 zero-new-table ruling; transient picture data only).
 */
@Service
public class AppautoDeviceThumbnailServiceImpl implements AppautoDeviceThumbnailService {

    private static final Logger log = LoggerFactory.getLogger(AppautoDeviceThumbnailServiceImpl.class);

    /** Assembled-thumbnail cache key prefix (tests/cleanup use this single spelling). */
    public static final String THUMB_CACHE_PREFIX = "appauto-wall:thumb:";

    /** Ceiling of running tasks probed per device call (wall poll reads the newest). */
    private static final int RUNNING_TASK_SCAN_LIMIT = 200;

    /** JPEG quality for wall cells — pictures are small, legibility beats fidelity. */
    private static final float THUMB_JPEG_QUALITY = 0.75f;

    private final MonitorFrameBuffer frameBuffer;
    private final AppAutoDispatchSupport dispatchSupport;
    private final AppautoConfigReader configReader;
    private final JdbcTemplate jdbcTemplate;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public AppautoDeviceThumbnailServiceImpl(MonitorFrameBuffer frameBuffer,
            AppAutoDispatchSupport dispatchSupport, AppautoConfigReader configReader,
            JdbcTemplate jdbcTemplate, StringRedisTemplate redis, ObjectMapper objectMapper) {
        this.frameBuffer = frameBuffer;
        this.dispatchSupport = dispatchSupport;
        this.configReader = configReader;
        this.jdbcTemplate = jdbcTemplate;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** Thumbnail cache key for one device (single spelling for read/clean faces). */
    public static String thumbCacheKey(long deviceId) {
        return THUMB_CACHE_PREFIX + deviceId;
    }

    @Override
    public AppautoDeviceThumbnailView thumbnail(long deviceId) {
        Map<String, Object> device = findDevice(deviceId);
        // Shared-slot rows carry no ownership (project_id NULL): the leasing task
        // may live under any project, so the scan keeps no project filter for them.
        Object rawProject = device.get("project_id");
        Long projectId = rawProject == null ? null : ((Number) rawProject).longValue();
        AppautoDeviceThumbnailView cached = readCache(deviceId);
        if (cached != null) {
            return cached;
        }
        Long taskId = findRunningTask(deviceId, projectId);
        if (taskId == null) {
            return AppautoDeviceThumbnailView.withoutFrame(deviceId, null,
                    AppautoDeviceThumbnailView.STATE_IDLE, "该设备当前无在跑任务");
        }
        BufferedImage frame = newestFrame(taskId);
        if (frame == null) {
            return AppautoDeviceThumbnailView.withoutFrame(deviceId, taskId,
                    AppautoDeviceThumbnailView.STATE_NO_FRAME,
                    "任务 #" + taskId + " 暂无实时帧：执行器未推流或画面超过保活窗口");
        }
        AppautoDeviceThumbnailView view = buildLive(deviceId, taskId, frame);
        writeCache(deviceId, view);
        return view;
    }

    private Map<String, Object> findDevice(long deviceId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, project_id, serial FROM tp_appauto_device WHERE id = ?", deviceId);
        if (rows.isEmpty()) {
            throw new ServiceException("设备 #" + deviceId + " 不存在，请在设备墙重新选择设备",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Newest running app-auto task whose envelope addresses this device.
     *  A null project (shared-slot device) scans running tasks of every project. */
    private Long findRunningTask(long deviceId, Long projectId) {
        String scanTail = " ORDER BY started_at DESC NULLS LAST, id DESC LIMIT " + RUNNING_TASK_SCAN_LIMIT;
        List<Map<String, Object>> rows = projectId == null
                ? jdbcTemplate.queryForList("SELECT id, action_json FROM tp_exec_task WHERE status = 2" + scanTail)
                : jdbcTemplate.queryForList("SELECT id, action_json FROM tp_exec_task WHERE project_id = ? AND status = 2" + scanTail, projectId);
        for (Map<String, Object> row : rows) {
            Object parsed = dispatchSupport.leaseDeviceId((String) row.get("action_json"));
            if (parsed != null && ((Number) parsed).longValue() == deviceId) {
                return ((Number) row.get("id")).longValue();
            }
        }
        return null;
    }

    private BufferedImage newestFrame(long taskId) {
        Map<String, Object> view = frameBuffer.readFrames(taskId);
        Object raw = view.get("frames");
        List<?> frames = raw instanceof List<?> list ? list : List.of();
        if (frames.isEmpty()) {
            return null;
        }
        Object image = frames.get(0) instanceof Map<?, ?> map ? map.get("image") : null;
        if (!(image instanceof String text) || text.isBlank()) {
            return null;
        }
        String payload = text.startsWith("data:") ? text.substring(text.indexOf(',') + 1) : text;
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(payload)));
        } catch (Exception e) {
            log.warn("设备墙缩略：任务 #{} 最新帧解码失败（按无画面如实上报）：{}", taskId, e.getMessage());
            return null;
        }
    }

    private AppautoDeviceThumbnailView buildLive(long deviceId, long taskId, BufferedImage frame) {
        int targetWidth = configReader.wallThumbWidthPx();
        int width = Math.min(targetWidth, frame.getWidth());   // never upscale small feeds
        int height = Math.max(1, Math.round((float) frame.getHeight() * width / frame.getWidth()));
        BufferedImage thumb = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumb.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(frame, 0, 0, width, height, null);
        graphics.dispose();
        String base64;
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(thumb, "jpg", out);
            base64 = Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            throw new ServiceException("设备墙缩略图生成失败，请稍后刷新设备墙重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return new AppautoDeviceThumbnailView(deviceId, taskId,
                AppautoDeviceThumbnailView.STATE_LIVE, null, base64,
                width, height, frame.getWidth(), frame.getHeight());
    }

    private AppautoDeviceThumbnailView readCache(long deviceId) {
        String raw = redis.opsForValue().get(thumbCacheKey(deviceId));
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(raw, AppautoDeviceThumbnailView.class);
        } catch (Exception e) {
            log.warn("设备墙缩略缓存解析失败（重装配覆盖）：{}", e.getMessage());
            return null;
        }
    }

    private void writeCache(long deviceId, AppautoDeviceThumbnailView view) {
        try {
            redis.opsForValue().set(thumbCacheKey(deviceId),
                    objectMapper.writeValueAsString(view),
                    Duration.ofSeconds(configReader.wallThumbTtlSeconds()));
        } catch (Exception e) {
            log.warn("设备墙缩略缓存写入失败（下次轮询重装配）：{}", e.getMessage());
        }
    }
}
