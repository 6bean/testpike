package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.support.BatchReportFixture;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * <p>Project/case builders, finalize trigger and the project-keyed sweep are
 * shared through BatchReportFixture (single source); this class keeps only the
 * recipient/member and distribution-flavored builders.
 */
@SpringBootTest(classes = CoreApplication.class)
class T257BatchReportTest {

    @Autowired
    private BatchReportFixture fixture;
    @Autowired
    private com.sixbean.testpike.core.batch.domain.service.BatchService batchService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture member users created by this class (swept by id after the project sweep). */
    private final List<Long> fixtureUserIds = new ArrayList<>();

    @BeforeEach
    void setUp() {
        fixture.isolateSideChannels();
    }

    @AfterEach
    void tearDown() {
        fixture.sweepFixtureRows();
        SharedDbFixtureSweep.byIds(jdbcTemplate, "tp_user", fixtureUserIds);
        fixtureUserIds.clear();
    }

    
    private long createMember(long projectId, String username, int role) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, ?)",
                projectId, uid, role);
        fixtureUserIds.add(uid);
        return uid;
    }

    
    private long batchWithTasks(long projectId, int batchStatus, Object[][] tasks) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type, started_at) "
                        + "VALUES (?, ?, ?, ?, 1, now() - interval '200 seconds') RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime(), "I7 报告批次", batchStatus);
        assert batchId != null;
        for (Object[] t : tasks) {
            jdbcTemplate.update(
                    "INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, error_type, ai_failure_class) "
                            + "VALUES (?, ?, ?, ?, ?, ?)",
                    projectId, t[0], batchId, t[1], t[2], t[3]);
        }
        return batchId;
    }

    
    private BigDecimal expectedRate(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FILTER (WHERE t.status = 3)::numeric"
                        + " / NULLIF(count(*) FILTER (WHERE t.status IN (3, 4, 5)), 0)"
                        + " FROM tp_exec_task t WHERE t.batch_id = ?",
                BigDecimal.class, batchId);
    }


    @Test
    void 收口已完成_报告行落库与实算一致() {
        long pid = fixture.createProject("BR1");
        long c1 = fixture.createCase(pid, "冒烟一");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 3, null, null},
                {c1, 3, null, null},
                {c1, 6, null, null},
        });
        fixture.finalizeBatch(batchId);

        Map<String, Object> r = fixture.reportRow(batchId);
        assertEquals(4, ((Number) r.get("total")).intValue());
        assertEquals(3, ((Number) r.get("pass_cnt")).intValue());
        assertEquals(0, ((Number) r.get("fail_cnt")).intValue());
        assertEquals(1, ((Number) r.get("skip_cnt")).intValue());
        assertEquals(0, ((Number) r.get("timeout_cnt")).intValue());
        assertEquals(6, ((Number) r.get("batch_status")).intValue());
        BigDecimal rate = (BigDecimal) r.get("pass_rate");
        assertEquals(0, rate.compareTo(expectedRate(batchId)),
                "pass_rate 必须=PassRateSql.RATE_EXPR 同值（TC-O10-01 禁第二处计算）");
        assertNotNull(r.get("duration_ms"));
        long dur = ((Number) r.get("duration_ms")).longValue();
        assertTrue(dur >= 190_000 && dur <= 300_000, "duration_ms 应≈200 秒，实=" + dur);
    }


    @Test
    void 收口部分失败_分布与归因桶正确() {
        long pid = fixture.createProject("BR2");
        long c1 = fixture.createCase(pid, "下单");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 4, 2, 2},
                {c1, 4, null, null},
                {c1, 5, 2, 1},
                {c1, 5, 3, null},
        });
        fixture.finalizeBatch(batchId);

        Map<String, Object> r = fixture.reportRow(batchId);
        assertEquals(7, ((Number) r.get("batch_status")).intValue(), "has_fail→部分失败(7) 既有语义零破坏");
        assertEquals(5, ((Number) r.get("total")).intValue());
        assertEquals(1, ((Number) r.get("pass_cnt")).intValue());
        assertEquals(2, ((Number) r.get("fail_cnt")).intValue());
        assertEquals(2, ((Number) r.get("timeout_cnt")).intValue());

        Map<String, Object> dist = jdbcTemplate.queryForMap(
                "SELECT (fail_dist_json->>'0')::int z, (fail_dist_json->>'2')::int loc,"
                        + " (fail_dist_json->>'3')::int env, (fail_dist_json->>'1')::int prod,"
                        + " (fail_dist_json->>'4')::int dep FROM tp_batch_report WHERE batch_id = ?", batchId);
        assertEquals(2, ((Number) dist.get("loc")).intValue(), "error_type=2 定位错误 2 条");
        assertEquals(1, ((Number) dist.get("env")).intValue(), "error_type=3 环境 1 条");
        assertEquals(1, ((Number) dist.get("z")).intValue(), "error_type NULL 归 0 桶");
        assertNull(dist.get("prod"), "零任务分类不占键（缺省=0 由前端展示层处理）");
        assertNull(dist.get("dep"));

        Map<String, Object> attr = jdbcTemplate.queryForMap(
                "SELECT (attr_json->>'1')::int prod, (attr_json->>'2')::int loc,"
                        + " (attr_json->>'unclassified')::int unc FROM tp_batch_report WHERE batch_id = ?", batchId);
        assertEquals(1, ((Number) attr.get("prod")).intValue(), "AI 归因产品缺陷 1 条");
        assertEquals(1, ((Number) attr.get("loc")).intValue(), "AI 归因定位错误 1 条");
        assertEquals(2, ((Number) attr.get("unc")).intValue(), "ai_failure_class NULL=未归类桶（开关关/未归类同义）");
    }


    @Test
    void 收口重入_报告单行幂等() {
        long pid = fixture.createProject("BR3");
        long c1 = fixture.createCase(pid, "幂等");
        long batchId = batchWithTasks(pid, 4, new Object[][]{{c1, 4, 1, null}});
        fixture.finalizeBatch(batchId);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 4, finished_at = NULL WHERE id = ?", batchId);
        fixture.finalizeBatch(batchId);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_report WHERE batch_id = ?", Long.class, batchId);
        assertEquals(1L, rows, "重入=覆盖不叠行（uk(batch_id) 幂等根）");
    }


    @Test
    void 完成通知_testOwner收件_content与报告同源() {
        long pid = fixture.createProject("BR4");
        long owner = createMember(pid, "owner_br4", 2);
        jdbcTemplate.update("UPDATE tp_project SET test_owner_id = ? WHERE id = ?", owner, pid);
        long c1 = fixture.createCase(pid, "通知");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null}, {c1, 3, null, null}, {c1, 4, 1, null},
        });
        fixture.finalizeBatch(batchId);

        List<Map<String, Object>> notifs = jdbcTemplate.queryForList(
                "SELECT user_id, event_type, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = 'BATCH_FINISHED' AND project_id = ? ORDER BY id", pid);
        assertEquals(1, notifs.size(), "完成事件收件=test_owner 1 人 1 条");
        Map<String, Object> n = notifs.get(0);
        assertEquals(owner, ((Number) n.get("user_id")).longValue());
        assertEquals("BATCH_FINISHED:" + batchId + ":" + owner, n.get("dedup_key"));

        Map<String, Object> r = fixture.reportRow(batchId);
        String content = String.valueOf(n.get("content"));
        assertTrue(content.contains("成功 2"), "content 通过数=报告行同源，实文=" + content);
        assertTrue(content.contains("失败 1"), "content 失败数=报告行同源，实文=" + content);
        assertTrue(content.contains("通过率"), "content 含通过率（人话中文）");
        assertTrue(String.valueOf(n.get("title")).contains("I7 报告批次"), "标题带批次名");
    }


    @Test
    void 部分失败批_双事件并存职责分离() {
        long pid = fixture.createProject("BR5");
        long owner = createMember(pid, "owner_br5", 2);
        jdbcTemplate.update("UPDATE tp_project SET test_owner_id = ? WHERE id = ?", owner, pid);
        long c1 = fixture.createCase(pid, "双发");
        long batchId = batchWithTasks(pid, 4, new Object[][]{{c1, 4, 1, null}, {c1, 3, null, null}});
        fixture.finalizeBatch(batchId);

        Map<String, Object> cnt = jdbcTemplate.queryForMap(
                "SELECT count(*) FILTER (WHERE event_type = 'BATCH_FAILED') failed,"
                        + " count(*) FILTER (WHERE event_type = 'BATCH_FINISHED') finished"
                        + " FROM tp_notification WHERE user_id = ?", owner);
        assertEquals(1L, ((Number) cnt.get("failed")).longValue(), "BATCH_FAILED 1 条（失败清单语义）");
        assertEquals(1L, ((Number) cnt.get("finished")).longValue(), "BATCH_FINISHED 1 条（全量汇总语义）");
    }


    @Test
    void 取消批_零报告零通知() {
        long pid = fixture.createProject("BR6");
        long owner = createMember(pid, "owner_br6", 2);
        jdbcTemplate.update("UPDATE tp_project SET test_owner_id = ? WHERE id = ?", owner, pid);
        long c1 = fixture.createCase(pid, "取消");
        long batchId = batchWithTasks(pid, 8, new Object[][]{{c1, 7, null, null}});
        fixture.finalizeBatch(batchId);

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_report WHERE batch_id = ?", Long.class, batchId);
        assertEquals(0L, rows, "取消批条件更新 0 行=零报告写（advanced 守卫）");
        Long notifs = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'BATCH_FINISHED' AND project_id = ?",
                Long.class, pid);
        assertEquals(0L, notifs, "取消批零完成通知");
    }


    @Test
    void 报告读路径_快照直读与落库行一致() {
        long pid = fixture.createProject("BR7");
        long c1 = fixture.createCase(pid, "读路径");
        long batchId = batchWithTasks(pid, 4, new Object[][]{
                {c1, 3, null, null},
                {c1, 4, 2, 2},
                {c1, 5, 3, null},
        });
        fixture.finalizeBatch(batchId);

        var view = batchService.report(batchId);
        assertEquals(batchId, view.batchId());
        assertEquals(7, view.batchStatus());
        assertEquals("部分失败", view.batchStatusLabel(), "状态中文=BatchInfo.statusLabel 单源");
        assertEquals(3, view.total());
        assertEquals(1, view.passCount());
        assertEquals(1, view.failCount());
        assertEquals(1, view.timeoutCount());
        assertEquals(0, view.skipCount());
        BigDecimal stored = (BigDecimal) fixture.reportRow(batchId).get("pass_rate");
        assertEquals(0, view.passRate().compareTo(stored), "读路径通过率=快照行原值（禁重算）");
        assertEquals(1, view.failDist().get("2"), "error_type=2 定位错误 1 条");
        assertEquals(1, view.failDist().get("3"), "error_type=3 环境 1 条");
        assertEquals(1, view.attr().get("2"), "AI 归因定位错误 1 条");
        assertEquals(1, view.attr().get("unclassified"), "NULL 归因=unclassified 桶");
        assertNotNull(view.durationMs());
    }


    @Test
    void 报告读路径_无快照行404人话() {
        long pid = fixture.createProject("BR8");
        long c1 = fixture.createCase(pid, "在跑");
        long batchId = batchWithTasks(pid, 4, new Object[][]{{c1, 2, null, null}});
        com.sixbean.testpike.common.exception.ServiceException ex =
                assertThrows(com.sixbean.testpike.common.exception.ServiceException.class,
                        () -> batchService.report(batchId));
        assertTrue(ex.getMessage().contains("报告"), "人话点名报告缺失，实=" + ex.getMessage());
    }


    @Test
    void V100数据面_表列与幂等索引在位() {
        assertEquals(1, count("SELECT count(*) FROM information_schema.tables"
                + " WHERE table_name = 'tp_batch_report'"), "tp_batch_report 表（V100 手动落库实证）");
        assertEquals(5, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'tp_batch_report'
                  AND column_name IN ('fail_dist_json','attr_json','pass_rate','duration_ms','batch_status')
                """), "快照五列在册");
        assertEquals(1L, count("SELECT count(*) FROM pg_indexes WHERE tablename = 'tp_batch_report'"
                + " AND indexname = 'tp_batch_report_batch_id_key'"), "uk(batch_id)=幂等根");
    }

    private long count(String sql, Object... args) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class, args);
        return v == null ? 0 : v;
    }
}
