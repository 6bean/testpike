package com.sixbean.testpike.core.appauto.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementCaptureRequest;
import com.sixbean.testpike.core.appauto.domain.component.MonitorFrameBuffer;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.junit.jupiter.api.AfterEach;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Collaborative capture segment: the operator drags a rectangle on a live device
 * frame, the server crops that region out of the buffered frame, stores the
 * crop at several scales in object storage and files an IMAGE element profile
 * that references the archives (tp_appauto_element).
 * <p>Covered surface: crop geometry reaches the archive (pixel check on the
 * stored image, not just a key), scale list honoured, server defaults used when
 * no scale is requested, region outside the frame rejected with the real frame
 * size in the message, no buffered frame rejected, and the per-project unique
 * name rule still applies to captured elements.
 * <p>Frames are pushed through the real {@link MonitorFrameBuffer} (Redis of the
 * chain slot) and images through the real storage provider; the database is the
 * real dev instance. No mock on this surface — reading the archive back from
 * storage and from the table is the point of the segment.
 */
@SpringBootTest(classes = CoreApplication.class)
class AppautoElementCaptureServiceTest {

    private static final int FRAME_WIDTH = 240;
    private static final int FRAME_HEIGHT = 480;

    @Autowired
    private AppautoElementCaptureService captureService;
    @Autowired
    private MonitorFrameBuffer frameBuffer;
    @Autowired
    private ArtifactStorage artifactStorage;
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    private long projectId;
    private long taskId;
    private long emptyTaskId;

    @BeforeEach
    void setUp() {
        String code = "cap" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "协同取图测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        long seed = Math.abs(System.nanoTime() % 1_000_000L);
        taskId = 9_300_000_000L + seed;
        emptyTaskId = 9_400_000_000L + seed;
        clearFrames();
    }

    @AfterEach
    void tearDown() {
        clearFrames();
        jdbcTemplate.update("DELETE FROM tp_appauto_element WHERE project_id = ?", projectId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private void clearFrames() {
        redisTemplate.delete(MonitorFrameBuffer.framesKey(taskId));
        redisTemplate.delete(MonitorFrameBuffer.metaKey(taskId));
        redisTemplate.delete(MonitorFrameBuffer.framesKey(emptyTaskId));
        redisTemplate.delete(MonitorFrameBuffer.metaKey(emptyTaskId));
    }

    /** One synthetic PNG frame (blue patch where the crop will land) pushed through the real ring. */
    private void seedFrame() throws Exception {
        BufferedImage image = new BufferedImage(FRAME_WIDTH, FRAME_HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, FRAME_WIDTH, FRAME_HEIGHT);
        graphics.setColor(Color.BLUE);
        graphics.fillRect(20, 40, 100, 150);
        graphics.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        String base64 = Base64.getEncoder().encodeToString(out.toByteArray());
        assertEquals(1, frameBuffer.ingest(List.of(Map.of("taskId", taskId, "image", base64))),
                "夹具帧须经真实帧环受理");
    }

    private AppautoElementCaptureRequest request(String name, List<Double> scales) {
        return new AppautoElementCaptureRequest(taskId, 0, name, 20, 40, 100, 150, scales, 0.85);
    }

    private Map<String, Object> elementRow(long elementId) {
        return jdbcTemplate.queryForMap("SELECT project_id, name, locator_type, "
                + "locator_config::text AS locator_config, variants::text AS variants "
                + "FROM tp_appauto_element WHERE id = ?", elementId);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> variantsOf(String json) throws Exception {
        return objectMapper.readValue(json, List.class);
    }

    @Test
    void croppedRegionIsArchivedAtEachRequestedScale() throws Exception {
        seedFrame();
        long elementId = captureService.captureFromFrame(projectId,
                request("登录按钮-实时取图", List.of(1.0, 0.8)));

        Map<String, Object> row = elementRow(elementId);
        assertEquals("IMAGE", row.get("locator_type"));
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        assertEquals("登录按钮-实时取图", row.get("name"));

        List<Map<String, Object>> variants = variantsOf((String) row.get("variants"));
        assertEquals(2, variants.size(), "两个倍率应各留一份归档");
        assertEquals("100x150", variants.get(0).get("resolution"));
        assertEquals("80x120", variants.get(1).get("resolution"));

        for (Map<String, Object> variant : variants) {
            String key = String.valueOf(variant.get("artifactKey"));
            assertTrue(key.contains("/appauto-element/"), "归档键走元素取图存储场景: " + key);
            assertTrue(artifactStorage.exists(key), "变体图像必须真实落到对象存储: " + key);
            byte[] stored = artifactStorage.readBytesQuietly(key);
            assertNotNull(stored, "归档图像应可回读: " + key);
            BufferedImage decoded = ImageIO.read(new ByteArrayInputStream(stored));
            assertEquals(variant.get("resolution"), decoded.getWidth() + "x" + decoded.getHeight(),
                    "回读尺寸必须等于申报分辨率（裁剪+缩放真实发生）");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> config = objectMapper.readValue((String) row.get("locator_config"), Map.class);
        assertEquals(taskId, ((Number) config.get("taskId")).longValue());
        @SuppressWarnings("unchecked")
        Map<String, Object> region = (Map<String, Object>) config.get("region");
        assertEquals(20, ((Number) region.get("x")).intValue());
        assertEquals(40, ((Number) region.get("y")).intValue());
        assertEquals(100, ((Number) region.get("width")).intValue());
        assertEquals(150, ((Number) region.get("height")).intValue());
    }

    @Test
    void emptyScaleListFallsBackToServerDefaultScales() throws Exception {
        seedFrame();
        long elementId = captureService.captureFromFrame(projectId,
                request("默认倍率取图", List.of()));

        List<Map<String, Object>> variants =
                variantsOf((String) elementRow(elementId).get("variants"));
        assertTrue(variants.isEmpty() == false, "缺省倍率至少产出一份归档");
        assertTrue(variants.stream()
                        .anyMatch(v -> "100x150".equals(v.get("resolution"))),
                "缺省倍率集必须含原始比例 100x150");
    }

    /**
     * A capture request without an explicit confidence floor must still file
     * the archive, falling back to the shipped element-table default (0.80).
     * The manual element form keeps demanding an explicit value: the fallback
     * belongs to the capture use case, and the single-point range validation
     * in AppautoElementService stays untouched.
     */
    @Test
    void absentConfidenceFallsBackToShippedDefaultFloor() throws Exception {
        seedFrame();
        long elementId = captureService.captureFromFrame(projectId,
                new AppautoElementCaptureRequest(taskId, 0, "缺省阈值取图",
                        20, 40, 100, 150, List.of(1.0), null));

        Double stored = jdbcTemplate.queryForObject(
                "SELECT confidence_threshold FROM tp_appauto_element WHERE id = ?",
                Double.class, elementId);
        assertNotNull(stored, "取图建档必须写入置信下限，不得留空依赖库缺省");
        assertEquals(AppautoElementService.DEFAULT_CONFIDENCE_FLOOR, stored.doubleValue(), 1e-9,
                "缺省阈值须回填出厂缺省 0.80（与 db/schema-init.sql 元素表列缺省同源）");
    }

    @Test
    void regionOutsideTheFrameIsRejectedWithRealFrameSize() throws Exception {
        seedFrame();
        ServiceException failure = assertThrows(ServiceException.class,
                () -> captureService.captureFromFrame(projectId,
                        new AppautoElementCaptureRequest(taskId, 0, "越界取图",
                                200, 400, 100, 150, List.of(1.0), 0.85)));
        assertTrue(failure.getMessage().contains(String.valueOf(FRAME_WIDTH))
                        && failure.getMessage().contains(String.valueOf(FRAME_HEIGHT)),
                "报错要点名画面真实尺寸，当前：" + failure.getMessage());
    }

    @Test
    void missingLiveFrameIsRejectedHonestly() {
        ServiceException failure = assertThrows(ServiceException.class,
                () -> captureService.captureFromFrame(projectId,
                        new AppautoElementCaptureRequest(emptyTaskId, 0, "无帧取图",
                                0, 0, 10, 10, List.of(1.0), 0.85)));
        assertTrue(failure.getMessage().contains("实时帧"),
                "无帧时必须说明实时帧缺失，当前：" + failure.getMessage());
    }

    @Test
    void duplicateElementNameIsRejected() throws Exception {
        seedFrame();
        captureService.captureFromFrame(projectId, request("同名元素", List.of(1.0)));
        ServiceException failure = assertThrows(ServiceException.class,
                () -> captureService.captureFromFrame(projectId, request("同名元素", List.of(1.0))));
        assertTrue(failure.getMessage().contains("同名"),
                "重名须沿用元素库唯一名规则，当前：" + failure.getMessage());
    }
}
