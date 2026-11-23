package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateRequest;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.controller.AiBatchInternalController;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Window-feed classification on the batch creation path (verification-window
 * feed priority channel). Covers the pure whitelist rule, the service decision
 * that persists tp_ai_batch.window_class, and the controller publish loop that
 * must route by that decision. Real dev database, no mocks; the only test
 * double is a capturing publisher stub at L2 to observe the MQ send posture
 * without touching the broker (infrastructure isolation per verification tier).
 */
@SpringBootTest(classes = CoreApplication.class)
class AiBatchWindowFeedClassificationTest {

    private static final long PROJECT = 10L;
    private static final long OPERATOR = 5L;

    @Autowired
    private AiBatchService aiBatchService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigService configService;

    private long batchFloor;
    private long taskFloor;

    @BeforeEach
    void markWatermarks() {
        batchFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_ai_batch");
        taskFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_ai_task");
    }

    @AfterEach
    void sweepRunRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_ai_batch", batchFloor);
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_ai_task", taskFloor);
        // Restore the shipped factory posture so a classification test can never
        // leak a widened whitelist or a flipped switch into the next class.
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, "", "string");
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_PRIORITY_ENABLED, "true", "bool");
    }

    // ── pure rule: comma-separated project ids ──

    @Test
    void 白名单规则_逗号清单命中与缺席() {
        assertTrue(AiBatchServiceImpl.windowWhitelisted("3,7", 7));
        assertTrue(AiBatchServiceImpl.windowWhitelisted("3,7", 3));
        assertFalse(AiBatchServiceImpl.windowWhitelisted("3,7", 4));
        assertFalse(AiBatchServiceImpl.windowWhitelisted("", 1));
        assertFalse(AiBatchServiceImpl.windowWhitelisted(null, 1));
        // Malformed or blank tokens are ignored, never fatal: "a, 10 ,,9" = {10,9}.
        assertTrue(AiBatchServiceImpl.windowWhitelisted("a, 10 ,,9", 10));
        assertFalse(AiBatchServiceImpl.windowWhitelisted("a, 10 ,,9", 1));
    }

    // ── service decision: three doors, persisted window_class ──

    @Test
    void 声明且命中白名单_批次落窗类标识并返回优先标记() {
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, String.valueOf(PROJECT), "string");
        AiBatchCreateResult result = aiBatchService.createBatch(input(true));
        assertTrue(result.windowFeed(), "declared + enabled + whitelisted must route as a window feed");
        assertEquals(1, windowClass(result.batchId()));
    }

    @Test
    void 未声明_走常规队列不占优先() {
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, String.valueOf(PROJECT), "string");
        AiBatchCreateResult result = aiBatchService.createBatch(input(false));
        assertFalse(result.windowFeed());
        assertEquals(0, windowClass(result.batchId()));
    }

    @Test
    void 白名单缺席_声明被降级为常规队列() {
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, "999999", "string");
        AiBatchCreateResult result = aiBatchService.createBatch(input(true));
        assertFalse(result.windowFeed(), "an unlisted project must not buy priority by header");
        assertEquals(0, windowClass(result.batchId()));
    }

    @Test
    void 白名单留空_任何声明都不启用优先() {
        AiBatchCreateResult result = aiBatchService.createBatch(input(true));
        assertFalse(result.windowFeed(), "empty whitelist = fail closed for every project");
        assertEquals(0, windowClass(result.batchId()));
    }

    @Test
    void 总开关关闭_白名单命中也走常规队列() {
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, String.valueOf(PROJECT), "string");
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_PRIORITY_ENABLED, "false", "bool");
        AiBatchCreateResult result = aiBatchService.createBatch(input(true));
        assertFalse(result.windowFeed(), "the hot switch off must restore the single-queue behavior");
        assertEquals(0, windowClass(result.batchId()));
    }

    // ── controller glue: the publish loop follows the decision ──

    @Test
    void 控制器发布循环按裁决结果走窗路由() {
        writeConfig(AiBatchServiceImpl.KEY_WINDOW_WHITELIST, String.valueOf(PROJECT), "string");
        CapturingAiGenPublisher publisher = new CapturingAiGenPublisher();
        AiBatchInternalController controller =
                new AiBatchInternalController(aiBatchService, publisher);
        AiBatchCreateRequest request = new AiBatchCreateRequest(
                PROJECT, List.of("窗路由夹具甲_" + System.nanoTime(), "窗路由夹具乙_" + System.nanoTime()),
                null, null, true);

        controller.create(request, OPERATOR);

        assertEquals(List.of(true, true), publisher.feedFlags(),
                "every fan-out publish of a window batch must carry the window flag");
    }

    @Test
    void 控制器发布循环_常规批次逐条走常规路由() {
        CapturingAiGenPublisher publisher = new CapturingAiGenPublisher();
        AiBatchInternalController controller =
                new AiBatchInternalController(aiBatchService, publisher);
        AiBatchCreateRequest request = new AiBatchCreateRequest(
                PROJECT, List.of("常规路由夹具_" + System.nanoTime()), null, null, false);

        controller.create(request, OPERATOR);

        assertEquals(List.of(false), publisher.feedFlags());
    }

    // ── fixtures ──

    private AiBatchCreateInput input(boolean declared) {
        return new AiBatchCreateInput(PROJECT, OPERATOR,
                BatchItemNormalizer.normalize(null,
                        List.of("窗类标识夹具需求_" + System.nanoTime())), declared);
    }

    private int windowClass(long batchId) {
        Integer value = jdbcTemplate.queryForObject(
                "SELECT window_class FROM tp_ai_batch WHERE id = ?", Integer.class, batchId);
        return value == null ? -1 : value;
    }

    /** Delete + create through the config service so the cache is refreshed — a bare SQL write would leave a stale value (even an empty value must ride the create channel so the cache sees the empty row). */
    private void writeConfig(String key, String value, String valueType) {
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?", key);
        configService.create(1L, new ConfigInput(key, value, "系统", valueType, 0, "验证窗优先通道夹具配置（测试写入）"));
    }
}
