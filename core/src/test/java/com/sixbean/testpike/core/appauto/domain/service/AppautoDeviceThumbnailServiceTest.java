package com.sixbean.testpike.core.appauto.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Device-wall thumbnail segment: the newest buffered monitor frame of the running
 * app-auto task that addresses a device is downscaled into a wall-sized JPEG,
 * short-cached for the one-frame-per-second wall poll, and every non-live cell
 * states its cause honestly (no task = IDLE, task without frames = NO_FRAME).
 * <p>Frames go through the real {@link MonitorFrameBuffer} (Redis of the chain
 * slot) and tasks through the real slot database; device attribution must come
 * from the dispatch envelope (leaseRef.deviceId), never from the serial, so two
 * devices with two running tasks must not swap thumbnails. No mock on this
 * surface — reading the thumbnail pixels back is the point of the segment.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoDeviceThumbnailServiceTest {

    private static final int FRAME_WIDTH = 540;
    private static final int FRAME_HEIGHT = 960;
    private static final int THUMB_WIDTH = 240;

    @Autowired
    private AppautoDeviceThumbnailService thumbnailService;
    @Autowired
    private MonitorFrameBuffer frameBuffer;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "wt" + Long.toString(System.nanoTime() % 46773514172L, 36);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "设备墙缩略测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
    }

    private long newDevice(String name) {
        String serial = "wt-" + Long.toHexString(System.nanoTime());
        jdbcTemplate.update(
                "INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                        + " VALUES (?, ?, ?, 'ADB')",
                projectId, name, serial);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE project_id = ? AND serial = ?",
                Long.class, projectId, serial);
    }

    /** Running task whose dispatch envelope addresses the given device. */
    private long newRunningTask(long deviceId, String serial) {
        try {
            String envelope = objectMapper.writeValueAsString(Map.of(
                    "kind", "app-auto-run",
                    "leaseRef", Map.of("deviceId", deviceId, "serial", serial)));
            jdbcTemplate.update(
                    "INSERT INTO tp_exec_task (project_id, case_id, status, action_json,"
                            + " started_at) VALUES (?, 0, 2, ?, now())",
                    projectId, envelope);
            return jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_exec_task WHERE project_id = ? AND action_json = ?"
                            + " AND status = 2 ORDER BY id DESC LIMIT 1",
                    Long.class, projectId, envelope);
        } catch (Exception e) {
            throw new IllegalStateException("envelope fixture failed", e);
        }
    }

    private String serialOf(long deviceId) {
        return jdbcTemplate.queryForObject(
                "SELECT serial FROM tp_appauto_device WHERE id = ?", String.class, deviceId);
    }

    /** Solid-colour PNG frame pushed through the real frame ring. */
    private void seedFrame(long taskId, Color color) {
        BufferedImage img = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(color);
        g.fillRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            String b64 = Base64.getEncoder().encodeToString(out.toByteArray());
            int accepted = frameBuffer.ingest(List.of(Map.of("taskId", taskId, "image", b64)));
            assertEquals(1, accepted, "fixture frame must pass the real ring gate");
        } catch (Exception e) {
            throw new IllegalStateException("frame fixture failed", e);
        }
    }

    private BufferedImage decodeThumb(String base64Image) {
        try {
            return ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(base64Image)));
        } catch (Exception e) {
            throw new AssertionError("thumbnail is not a decodable image", e);
        }
    }

    @Test
    void liveTaskWithFrameYieldsDownscaledThumbnailAndCache() {
        long deviceId = newDevice("墙A");
        long taskId = newRunningTask(deviceId, serialOf(deviceId));
        seedFrame(taskId, Color.RED);

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_LIVE, view.state());
        assertEquals(taskId, view.taskId());
        assertNotNull(view.imageBase64(), "live cell must carry the thumbnail");
        BufferedImage thumb = decodeThumb(view.imageBase64());
        assertEquals(THUMB_WIDTH, thumb.getWidth(),
                "thumbnail width must honour the shipped wall width");
        assertEquals(Math.round((double) THUMB_WIDTH * FRAME_HEIGHT / FRAME_WIDTH),
                thumb.getHeight(), "aspect ratio must be preserved");
        assertEquals(FRAME_WIDTH, view.sourceWidth());
        assertEquals(FRAME_HEIGHT, view.sourceHeight());
        assertNotNull(redisTemplate.opsForValue().get(
                "appauto-wall:thumb:" + deviceId), "assembled thumbnail must be short-cached");
    }

    @Test
    void idleDeviceStatesNoRunningTaskWithoutError() {
        long deviceId = newDevice("墙B");

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_IDLE, view.state());
        assertNull(view.imageBase64());
        assertNotNull(view.reason());
        assertTrue(view.reason().contains("无在跑任务"), "idle reason must say what is missing");
    }

    @Test
    void runningTaskWithoutBufferedFrameStatesNoFrame() {
        long deviceId = newDevice("墙C");
        newRunningTask(deviceId, serialOf(deviceId));

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_NO_FRAME, view.state());
        assertNull(view.imageBase64());
        assertNotNull(view.reason());
    }

    @Test
    void envelopeAttributionPicksTheTaskOfTheAskedDevice() {
        long device1 = newDevice("墙D1");
        long device2 = newDevice("墙D2");
        long task1 = newRunningTask(device1, serialOf(device1));
        long task2 = newRunningTask(device2, serialOf(device2));
        seedFrame(task1, Color.RED);
        seedFrame(task2, Color.BLUE);

        BufferedImage thumb2 = decodeThumb(
                thumbnailService.thumbnail(device2).imageBase64());
        Color mid = new Color(thumb2.getRGB(thumb2.getWidth() / 2, thumb2.getHeight() / 2));
        assertTrue(mid.getBlue() > mid.getRed(),
                "device 2 cell must show the blue frame of its own task");
    }

    @Test
    void finishedTaskDoesNotCountAsRunning() {
        long deviceId = newDevice("墙E");
        long taskId = newRunningTask(deviceId, serialOf(deviceId));
        seedFrame(taskId, Color.GREEN);
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 3 WHERE id = ?", taskId);

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_IDLE, view.state());
        assertNull(view.imageBase64(), "a finished task must not keep the cell alive");
    }

    /** Shared-slot device (cross-project public asset, empty ownership column). */
    private long newSharedDevice(String name) {
        String serial = "wts-" + Long.toHexString(System.nanoTime());
        jdbcTemplate.update(
                "INSERT INTO tp_appauto_device (project_id, name, serial, device_type)"
                        + " VALUES (NULL, ?, ?, 'ADB')",
                name, serial);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM tp_appauto_device WHERE serial = ?", Long.class, serial);
    }

    @Test
    void sharedDeviceWithoutRunningTaskYieldsIdleCell() {
        long deviceId = newSharedDevice("墙F-共享");

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_IDLE, view.state(),
                "a shared device with no running task must serve the honest IDLE cell, not error");
        assertNull(view.imageBase64());
    }

    @Test
    void sharedDeviceWithRunningTaskFromAnyProjectYieldsFrameCell() {
        long deviceId = newSharedDevice("墙G-共享");
        long taskId = newRunningTask(deviceId, serialOf(deviceId));

        AppautoDeviceThumbnailView view = thumbnailService.thumbnail(deviceId);

        assertEquals(AppautoDeviceThumbnailView.STATE_NO_FRAME, view.state(),
                "a running task leasing the shared device must be attributed across projects");
        assertEquals(taskId, view.taskId(), "the attributed task is the leasing one");
    }
}
