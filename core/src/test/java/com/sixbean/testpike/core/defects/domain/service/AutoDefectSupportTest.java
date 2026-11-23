package com.sixbean.testpike.core.defects.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.batch.domain.component.AutoDefectSupport;
import com.sixbean.testpike.core.batch.domain.component.BatchFinalizeSupport;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.defects.api.dto.DefectCreateInput;
import com.sixbean.testpike.core.defects.api.dto.DefectLibraryInfo;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AutoDefectSupportTest {

    @Autowired
    private AutoDefectSupport autoDefectSupport;
    @Autowired
    private DefectLibraryService defectLibraryService;
    @Autowired
    private BatchFinalizeSupport batchFinalizeSupport;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped projects, the factory admin user and notifications on the
 * shared dev database. tp_comment is polymorphic with no FK into
 * tp_defect, so its join purge runs BEFORE the project sweep (shared
 * helper, R40). The member usernames below are fixed literals whose rows
 * survive the project sweep, so they self-heal as a family BEFORE the
 * tp_user delete (tp_user_project.user_id declares no FK — orphan
 * memberships would poison the next round's assignment chain).
 */
    private static final List<String> MEMBER_USERNAMES =
            List.of("dev_a", "boss_a", "boss_b", "worker_c", "boss_c", "human_a");

    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.defectCommentRowsOfProjects(jdbcTemplate, trackedProjects);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
        for (String username : MEMBER_USERNAMES) {
            jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id IN"
                    + " (SELECT id FROM tp_user WHERE username = ?)", username);
            jdbcTemplate.update("DELETE FROM tp_user WHERE username = ?", username);
        }
        // restore the two switches to defaults (a case that flips them pollutes other suites)
        restoreSwitch("1", "24");
    }

    private void restoreSwitch(String enabled, String windowHours) {
        upsertConfig("defect.auto-create.enabled", enabled, "int");
        upsertConfig("defect.auto-create.dedup-window", windowHours, "int");
    }

    private void upsertConfig(String key, String value, String type) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '系统', ?, 0, '测试期临时配置值')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value, type);
    }

    private long createProject(String code) {
        long id = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
        // The service-path create registers creator id=1 as project owner; the
        // assignment chain under test must only see the fixture's real
        // members, so the membership row is dropped for this suite's projects.
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = 1", id);
        return id;
    }

    private long createCase(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    
    private long createMember(long projectId, String username, int role) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, ?)",
                projectId, uid, role);
        return uid;
    }

    
    private long failingBatch(long projectId, Object[][] tasks) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, ?, 4, 1) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime(), "夜炸批次");
        assert batchId != null;
        for (Object[] t : tasks) {
            jdbcTemplate.update("""
                    INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, error_type, error_detail,
                                              steps_timeline)
                    VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)
                    """, projectId, t[0], batchId, t[1], t[2], t[3],
                    "[{\"stepNo\":" + t[4] + ",\"status\":\"fail\"}]");
        }
        return batchId;
    }

    private List<Map<String, Object>> defectsOf(long caseId) {
        return jdbcTemplate.queryForList(
                "SELECT id, title, assignee_id, status, source, fail_signature, last_seen_at, occurrence_count"
                        + " FROM tp_defect WHERE case_id = ? ORDER BY id", caseId);
    }


    @Test
    void 同签名三任务_一单两追评_异签名另立单() {
        long pid = createProject("AD1");
        long caseA = createCase(pid, "登录冒烟");
        long caseB = createCase(pid, "下单冒烟");
        long batchId = failingBatch(pid, new Object[][]{
                {caseA, 4, 2, "元素 .btn-submit 等待超时 12345ms 未出现", 3},
                {caseA, 4, 2, "元素 .btn-submit 等待超时 987ms 未出现", 3},
                {caseA, 5, 2, "元素 .btn-submit 等待超时 30000ms 未出现", 3},
                {caseB, 4, 1, "接口返回 500 期望 200", 1},
        });

        autoDefectSupport.onBatchPartialFailed(batchId);

        List<Map<String, Object>> da = defectsOf(caseA);
        assertEquals(1, da.size(), "同签名 3 任务只能 1 张单（TC-V3-01 防夜炸）");
        assertEquals(3, ((Number) da.get(0).get("occurrence_count")).intValue(),
                "首建计 1 + 同签名复现计 2 = 3 见");
        assertEquals(2, count("SELECT count(*) FROM tp_comment WHERE host_type = 'defect'"
                + " AND host_id = ? AND source = 2", da.get(0).get("id")),
                "后两条同签名任务=追评论不追单");
        List<Map<String, Object>> db = defectsOf(caseB);
        assertEquals(1, db.size(), "异 case 独立建单");
        assertEquals(2, ((Number) db.get(0).get("source")).intValue(), "source=2 自动建单（1=人工，前端标识数据源）");
    }

    @Test
    void 签名不同_同用例分单() {
        long pid = createProject("AD2");
        long caseId = createCase(pid, "购物车");
        long batchId = failingBatch(pid, new Object[][]{
                {caseId, 4, 2, "元素 .btn-buy 等待超时", 3},
                {caseId, 4, 1, "接口返回 500 期望 200", 5},
        });
        autoDefectSupport.onBatchPartialFailed(batchId);
        assertEquals(2, defectsOf(caseId).size(), "错误本质不同=分单（TC-V3-01 后半）");
    }


    @Test
    void 指派链_devOwner在位_指派本人() {
        long pid = createProject("AD3");
        long devOwner = createMember(pid, "dev_a", 1);
        createMember(pid, "boss_a", 2);
        jdbcTemplate.update("UPDATE tp_project SET dev_owner_id = ? WHERE id = ?", devOwner, pid);
        long caseId = createCase(pid, "查询");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 1, "空指针异常", 2}});
        autoDefectSupport.onBatchPartialFailed(batchId);
        assertEquals(devOwner, ((Number) defectsOf(caseId).get(0).get("assignee_id")).longValue());
    }

    @Test
    void 指派链_无devOwner_回退项目负责人() {
        long pid = createProject("AD4");
        long owner = createMember(pid, "boss_b", 2);
        long caseId = createCase(pid, "结算");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 1, "金额校验失败", 2}});
        autoDefectSupport.onBatchPartialFailed(batchId);
        assertEquals(owner, ((Number) defectsOf(caseId).get(0).get("assignee_id")).longValue());
    }

    @Test
    void 指派链_双缺_待指派加通知不抛() {
        long pid = createProject("AD5");
        long plainMember = createMember(pid, "worker_c", 1);
        createMember(pid, "boss_c", 2);
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND role = 2", pid);
        jdbcTemplate.update("UPDATE tp_project SET dev_owner_id = NULL, created_by = NULL WHERE id = ?", pid);
        long caseId = createCase(pid, "退款");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 3, "环境不可用连接被拒", 1}});
        autoDefectSupport.onBatchPartialFailed(batchId);
        Map<String, Object> d = defectsOf(caseId).get(0);
        assertNull(d.get("assignee_id"), "缺链=待指派（assignee NULL 不阻断建单）");
        Long notified = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'DEFECT_UNASSIGNED' AND user_id = ?",
                Long.class, plainMember);
        assertTrue(notified != null && notified >= 1, "待指派必须通知项目成员兜底");
    }


    @Test
    void 钩子重投_不重复建单不重复追评() {
        long pid = createProject("AD6");
        long caseId = createCase(pid, "重投");
        long batchId = failingBatch(pid, new Object[][]{
                {caseId, 4, 2, "断言失败 期望A 实际B 耗时120ms", 3},
                {caseId, 4, 2, "断言失败 期望A 实际B 耗时350ms", 3},
        });
        autoDefectSupport.onBatchPartialFailed(batchId);
        autoDefectSupport.onBatchPartialFailed(batchId);
        List<Map<String, Object>> ds = defectsOf(caseId);
        assertEquals(1, ds.size(), "重投不重复建单");
        assertEquals(2, count("SELECT count(*) FROM tp_comment WHERE host_id = ?", ds.get(0).get("id")),
                "追评按任务幂等（ref_key 唯一），重投不翻倍");
    }


    @Test
    void 已关闭单_窗内复现重开_窗外新立() {
        long pid = createProject("AD7");
        long caseId = createCase(pid, "窗");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 1, "除零异常", 2}});
        autoDefectSupport.onBatchPartialFailed(batchId);
        long defectId = ((Number) defectsOf(caseId).get(0).get("id")).longValue();
        jdbcTemplate.update("UPDATE tp_defect SET status = 5,"
                + " last_seen_at = now() - interval '1 hour' WHERE id = ?", defectId);
        long batch2 = failingBatch(pid, new Object[][]{{caseId, 4, 1, "除零异常", 2}});
        autoDefectSupport.onBatchPartialFailed(batch2);
        assertEquals(1, defectsOf(caseId).size(), "窗内复现不另立新单");
        assertEquals(6, ((Number) defectsOf(caseId).get(0).get("status")).intValue(), "关闭态窗内复现=重新打开(6)");

        jdbcTemplate.update("UPDATE tp_defect SET status = 5, last_seen_at = now() - interval '100 hours'"
                + " WHERE id = ?", defectId);
        long batch3 = failingBatch(pid, new Object[][]{{caseId, 4, 1, "除零异常", 2}});
        autoDefectSupport.onBatchPartialFailed(batch3);
        assertEquals(2, defectsOf(caseId).size(), "窗外复现另立新单");
        assertEquals(1, ((Number) defectsOf(caseId).get(1).get("status")).intValue());
    }


    @Test
    void 开关关闭_零建单() {
        upsertConfig("defect.auto-create.enabled", "0", "int");
        long pid = createProject("AD8");
        long caseId = createCase(pid, "关闭");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 1, "任何异常", 1}});
        autoDefectSupport.onBatchPartialFailed(batchId);
        assertTrue(defectsOf(caseId).isEmpty(), "defect.auto-create.enabled=0 必须零建单");
    }


    @Test
    void 批次收口共享段触发自动建单() {
        long pid = createProject("AD9");
        long caseId = createCase(pid, "收口接线");
        long batchId = failingBatch(pid, new Object[][]{{caseId, 4, 1, "回调接线异常", 2}});
        batchFinalizeSupport.onTasksTimedOut(List.of(batchId));
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_batch WHERE id = ?", Integer.class, batchId);
        assertEquals(7, status, "收口推进部分失败(7) 既有语义零破坏");
        assertEquals(1, defectsOf(caseId).size(), "收口→自动建单挂点必须经共享段生效");
    }


    @Test
    void V82数据面_列与表与索引在位() {
        assertEquals(4, count("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'tp_defect'
                  AND column_name IN ('source','fail_signature','last_seen_at','occurrence_count')
                """, null), "tp_defect 四新列（source/fail_signature/last_seen_at/occurrence_count）");
        assertEquals(1, count("""
                SELECT count(*) FROM information_schema.tables WHERE table_name = 'tp_comment'
                """, null), "tp_comment 多态评论表（V14 宿主面最小建，T240 扩展）");
        assertNotNull(jdbcTemplate.queryForObject("""
                SELECT indexdef FROM pg_indexes WHERE tablename = 'tp_defect'
                  AND indexdef ILIKE '%case_id%' AND indexdef ILIKE '%fail_signature%'
                  AND indexdef ILIKE '%WHERE%'
                LIMIT 1
                """, String.class), "开放态自动单部分唯一索引（case_id+fail_signature WHERE status<>5）");
    }


    @Test
    void 人工建单_source为1() {
        long pid = createProject("AD10");
        long reporter = createMember(pid, "human_a", 1);
        DefectLibraryInfo info = defectLibraryService.create(reporter, new DefectCreateInput(
                pid, null, null, "人工缺陷", "手填", null, null, null, null));
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT source, fail_signature FROM tp_defect WHERE id = ?", info.id());
        assertEquals(1, ((Number) row.get("source")).intValue(), "人工链路默认 source=1（存量语义零漂移）");
        assertNull(row.get("fail_signature"), "人工单无失败签名");
    }

    private long count(String sql, Object id) {
        Long v = id == null ? jdbcTemplate.queryForObject(sql, Long.class)
                : jdbcTemplate.queryForObject(sql, Long.class, id);
        return v == null ? 0 : v;
    }
}
