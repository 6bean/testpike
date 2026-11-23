package com.sixbean.testpike.core.ai.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stall sweep for zombie "generating" AI tasks (filed by the audit).
 * <p>A generation task rides tp_ai_task.status=2 from dispatch until ai-service
 * writes back; when the consumer dies mid-flight (restart, MQ loss, killed LLM
 * call) nothing reclaims the row — live audit found 12 such rows idle for over
 * five hours (oldest 9 days), the queue panel keeps showing "generating", the
 * backlog gauge overstates and the user has no recovery path. The sweeper
 * marks status=2 rows whose updated_at passed the K8 window
 * ai.task.running-stall-minutes (seeded 60) failed through the service
 * markFailed door (single source: error code/message columns + restartable
 * <p>Behaviour under test (real dev database, zero mocks — posture of
 * DocumentExtractStallSweeperTest):
 * <ul>
 * <li>stale status=2 rows settle to 4 with SERVICE_ERROR and a wording that
 * rows inside the window are never touched;</li>
 * <li>other statuses (1/3/4/5) are untouched regardless of age, and untouched
 * rows keep a NULL error message;</li>
 * <li>idempotent: a second round settles nothing new;</li>
 * <li>fail-closed window parsing: garbage/blank falls back to the seeded 60,
 * a non-positive window refuses the sweep (never "fail everything");</li>
 * <li>the window rides the K8 key read fresh on every run (hot-update).</li>
 * </ul>
 * Fixture rows live under a private project so cleanup and assertions only
 * ever look at this test's own rows (R37: no factory-table truncation).
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=true")
class AiTaskRunningStallSweeperTest {

    /** K8 key shared with the sweeper (seeded default 60). */
    static final String STALL_MINUTES_KEY = "ai.task.running-stall-minutes";

    @Autowired
    private AiTaskRunningStallSweeper sweeper;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void setUp() {
        String code = "atswp" + (System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "生成任务僵尸清扫测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE code = ?", Long.class, code);
        configCacheHolder.put(STALL_MINUTES_KEY, "60");
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM tp_ai_task WHERE project_id = ?", projectId);
        configCacheHolder.remove(STALL_MINUTES_KEY);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    /** Insert a task row pinned to the given status, aged back by an interval. */
    private long seedTask(int status, String ageInterval) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_ai_task (project_id, operator_id, task_type, status, "
                        + "requirement_text, updated_at) VALUES (?, 1, 1, ?, 'sweep-fixture', "
                        + "now() - interval '" + ageInterval + "') RETURNING id",
                Long.class, projectId, status);
    }

    private Integer statusOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM tp_ai_task WHERE id = ?", Integer.class, id);
    }

    private String errorCodeOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT error_code FROM tp_ai_task WHERE id = ?", String.class, id);
    }

    private String errorMsgOf(long id) {
        return jdbcTemplate.queryForObject(
                "SELECT error_msg FROM tp_ai_task WHERE id = ?", String.class, id);
    }

    @Test
    void staleRunningRow_settlesFailed_withRestartDoor() {
        long zombie = seedTask(2, "90 minutes");

        int swept = sweeper.sweepStalled();

        assertTrue(swept >= 1, "超窗 status=2 行必须被收回（本轮 swept=" + swept + "）");
        assertEquals(4, statusOf(zombie), "僵尸生成中行应落败态 4（失败行可重新生成）");
        assertEquals("SERVICE_ERROR", errorCodeOf(zombie),
                "回写必须走失败分类三选一（MODEL_TIMEOUT/FORMAT_ERROR/SERVICE_ERROR）");
        String msg = errorMsgOf(zombie);
        assertNotNull(msg, "落败必须带人话 error_msg（禁静默改态）");
        assertTrue(msg.contains("重新生成"), "文案须点名恢复入口屏显字面「重新生成」，实测=" + msg);
        assertTrue(msg.length() <= 500, "error_msg VARCHAR(500) 不越界，实测=" + msg.length());
    }

    @Test
    void freshRunningRow_andOtherStatuses_areUntouched() {
        long freshRunning = seedTask(2, "5 minutes");
        long oldPending = seedTask(1, "3 hours");
        long oldDone = seedTask(3, "3 hours");
        long oldFailed = seedTask(4, "3 hours");
        long oldCancelled = seedTask(5, "3 hours");

        sweeper.sweepStalled();

        assertEquals(2, statusOf(freshRunning), "窗口内的生成中行=正常在途，禁误杀");
        assertEquals(1, statusOf(oldPending), "status=1 与本清扫无关");
        assertEquals(3, statusOf(oldDone), "status=3 与本清扫无关");
        assertEquals(4, statusOf(oldFailed), "已是败态的行不重复处置");
        assertEquals(5, statusOf(oldCancelled), "status=5 与本清扫无关");
        assertNull(errorMsgOf(oldPending), "未被清扫的行禁写入 error_msg");
    }

    @Test
    void secondRound_settlesNothingNew() {
        seedTask(2, "90 minutes");
        int first = sweeper.sweepStalled();
        int second = sweeper.sweepStalled();
        assertTrue(first >= 1, "第一轮应至少收回夹具行（实测 " + first + "）");
        assertEquals(0, second, "清扫幂等：第二轮对同一无存量面应归零");
    }

    @Test
    void windowRidesK8Key_hotUpdateAndFailClosed() {
        long borderline = seedTask(2, "90 minutes");
        configCacheHolder.put(STALL_MINUTES_KEY, "120"); // widen beyond fixture age
        sweeper.sweepStalled();
        assertEquals(2, statusOf(borderline), "窗口热更放宽到 120min 后，90min 行应留在途态");

        configCacheHolder.put(STALL_MINUTES_KEY, "garbage");
        sweeper.sweepStalled();
        assertEquals(4, statusOf(borderline), "非法值按缺省 60 回退，90min 行应被收回（fail-closed）");
    }

    @Test
    void nonPositiveWindow_refusesSweep_neverFailsEverything() {
        long fresh = seedTask(2, "5 minutes");
        long stale = seedTask(2, "3 hours");
        for (String bad : new String[]{"0", "-1"}) {
            configCacheHolder.put(STALL_MINUTES_KEY, bad);
            int swept = sweeper.sweepStalled();
            assertEquals(0, swept, "窗口 " + bad + " 应拒绝清扫而非全量落败");
            assertEquals(2, statusOf(fresh), "拒绝清扫=一切在途态不动");
            assertEquals(2, statusOf(stale), "拒绝清扫=超窗行也不动（等合法窗口）");
        }
    }
}
