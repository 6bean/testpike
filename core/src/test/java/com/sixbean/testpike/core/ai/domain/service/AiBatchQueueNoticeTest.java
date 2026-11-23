package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.domain.component.AiQueueNoticeAssembler;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.ai.domain.service.AiBatchService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Batch progress queue notice (verification-window feed priority channel).
 * Covers the pure three-part sentence assembly (reason / current backlog /
 * recovery estimate) and the service wiring that reads the hot-update keys
 * and only flags batches that still have waiting items. Real dev database,
 * no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class AiBatchQueueNoticeTest {

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
    }

    // ── pure assembler: three-part sentence rules ──

    @Test
    void 三段句_有耗时样本_原因积压数恢复预计齐备() {
        // backlog 8, avg 90s/task, concurrency 4 → ceil(8*90/4/60) = 3 minutes
        String notice = AiQueueNoticeAssembler.build(2, 8, 90L, 4, 3, 24);
        assertNotNull(notice);
        assertTrue(notice.contains("积压 8 条"), notice);
        assertTrue(notice.contains("本批次 2 条任务正在排队"), notice);
        assertTrue(notice.contains("预计约 3 分钟后完成当前排队"), notice);
        assertTrue(notice.contains("24 小时"), notice);
    }

    @Test
    void 三段句_预计分钟向上取整且至少一分钟() {
        // backlog 1, avg 10s, concurrency 4 → 2.5s → ceil = 1 minute (never 0)
        String notice = AiQueueNoticeAssembler.build(1, 5, 10L, 4, 3, 24);
        assertNotNull(notice);
        assertTrue(notice.contains("预计约 1 分钟"), notice);
    }

    @Test
    void 三段句_积压低于阈值_不给提示() {
        assertNull(AiQueueNoticeAssembler.build(2, 2, 90L, 4, 3, 24));
    }

    @Test
    void 三段句_本批次无等待任务_不给提示() {
        assertNull(AiQueueNoticeAssembler.build(0, 50, 90L, 4, 3, 24));
    }

    @Test
    void 三段句_无耗时样本_诚实说明预计暂缺且自动更新() {
        String notice = AiQueueNoticeAssembler.build(3, 9, null, 4, 3, 24);
        assertNotNull(notice);
        assertTrue(notice.contains("积压 9 条"), notice);
        assertTrue(notice.contains("暂无已完成任务可作耗时样本"), notice);
        assertTrue(notice.contains("自动更新"), notice);
        // Honest degradation must never ask the user to refresh by hand.
        assertTrue(!notice.contains("刷新"), notice);
    }

    // ── service wiring: hot keys + per-batch gating on real db ──

    @Test
    void 批次排队中_进度返回三段句提示且点名本批次等待数() {
        Long batchId = insertBatch(4);
        insertTasks(batchId, 4, 1);
        // One done sample exactly inside the shipped 24h sampling window:
        // created 2h ago, finished 1h ago → 3600s average for this slot db.
        insertDoneSample(3600);

        AiBatchInfo info = aiBatchService.get(batchId);
        assertNotNull(info);
        String notice = info.queueNotice();
        assertNotNull(notice, "waiting batch over threshold must carry a notice");
        assertTrue(notice.contains("本批次 4 条任务正在排队"), notice);
        Matcher m = Pattern.compile("积压 (\\d+) 条").matcher(notice);
        assertTrue(m.find(), notice);
        assertTrue(Integer.parseInt(m.group(1)) >= 4, notice);
    }

    @Test
    void 阈值热更调高_同样积压不再给提示() {
        setThreshold("100000");
        try {
            Long batchId = insertBatch(3);
            insertTasks(batchId, 3, 1);
            AiBatchInfo info = aiBatchService.get(batchId);
            assertNotNull(info);
            assertNull(info.queueNotice(),
                    "backlog below the raised threshold must stay silent");
        } finally {
            setThreshold("3");
        }
    }

    @Test
    void 批次全部完成_进度不带排队提示() {
        Long batchId = insertBatch(2);
        insertTasks(batchId, 2, 3);
        AiBatchInfo info = aiBatchService.get(batchId);
        assertNotNull(info);
        assertNull(info.queueNotice());
    }

    // ── fixtures ride the raw tables; the tested path is get() alone ──

    private Long insertBatch(int total) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_batch (project_id, operator_id, total) VALUES (10, 5, ?) RETURNING id",
                Long.class, total);
    }

    private void insertTasks(Long batchId, int count, int status) {
        for (int i = 0; i < count; i++) {
            jdbcTemplate.update(
                    "INSERT INTO tp_ai_task (project_id, operator_id, task_type, status, requirement_text, batch_id)"
                            + " VALUES (10, 5, 1, ?, ?, ?)",
                    status, "队列提示夹具需求" + i + "_" + System.nanoTime(), batchId);
        }
    }

    private void insertDoneSample(int durationSeconds) {
        jdbcTemplate.update(
                "INSERT INTO tp_ai_task (project_id, operator_id, task_type, status, requirement_text,"
                        + " created_at, updated_at) VALUES (10, 5, 1, 3, ?, now() - make_interval(secs => ? * 2),"
                        + " now() - make_interval(secs => ?))",
                "队列提示完成样本_" + System.nanoTime(), durationSeconds, durationSeconds);
    }

    private void setThreshold(String value) {
        // Delete + create ride the config service channel so the cache is
        // evicted and refilled — a bare SQL write would leave a stale value.
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key = ?",
                "ai.gen.window-priority.notice-backlog-threshold");
        configService.create(1L, new ConfigInput(
                "ai.gen.window-priority.notice-backlog-threshold", value, "系统", "int", 0,
                "生成队列等待提示的积压条数阈值（测试写入）"));
    }
}
