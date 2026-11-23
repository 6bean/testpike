package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseRollbackResult;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionDiff;
import com.sixbean.testpike.core.cases.api.dto.CaseVersionInfo;
import com.sixbean.testpike.core.cases.domain.component.ReviewAction;
import com.sixbean.testpike.common.exception.OptimisticLockingException;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseVersionServiceTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> versionTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep reaches this suite's cases, steps, version snapshots, scripts
        // and audit rows (case/script versions are purged by the sweep itself).
        ProjectFixtureRig.sweep(jdbcTemplate, versionTrackedProjects);
        versionTrackedProjects.clear();
    }

    
    private long seedOperator(long userId) {
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, userId, "admin");
        return userId;
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                versionTrackedProjects, "项目" + code, code);
    }

    private List<CaseStepInput> steps(String action, String assertion) {
        return List.of(new CaseStepInput(1, action, 0), new CaseStepInput(2, assertion, 1));
    }

    private CaseCreateInput input(long projectId, String title) {
        return new CaseCreateInput(projectId, title, "打开系统登录页", "登录成功进入首页",
                1, 2, "web", 1, null, 1, null, steps("输入账号密码", "页面出现登录成功提示"));
    }

    private Map<String, Object> changeMap(CaseVersionDiff diff, String field) {
        List<CaseVersionDiff> unused = null;
        return diff.changes().stream().filter(c -> c.field().equals(field))
                .map(c -> Map.<String, Object>of("field", c.field(), "before",
                        String.valueOf(c.before()), "after", String.valueOf(c.after())))
                .findFirst().orElse(null);
    }


    @Test
    void create_写入v1快照_版本列表可见() {
        seedOperator(1L);
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "登录功能测试"));

        List<CaseVersionInfo> versions = caseService.listVersions(info.id());
        assertEquals(1, versions.size(), "创建即写入 v1 快照");
        CaseVersionInfo v1 = versions.get(0);
        assertEquals(1, v1.version());
        assertEquals(CaseVersionInfo.CHANGE_EDIT, v1.changeType());
        assertEquals(1L, v1.operatorId());
        assertNotNull(v1.operatorName(), "操作人显示名（display_name 回退 username）");
        assertNotNull(v1.createdAt());
        assertFalse(v1.corrupted());
    }

    @Test
    void 编辑后快照v2_diff返回字段级before_after() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "登录功能测试"));
        caseService.update(1L, info.id(), new CaseUpdateInput("登录功能测试V2", null, null,
                null, null, null, null, steps("输入账号密码并点击登录", "页面出现登录成功提示"), null, null), 1);

        List<CaseVersionInfo> versions = caseService.listVersions(info.id());
        assertEquals(2, versions.size());
        assertEquals(2, versions.get(0).version(), "列表最新在前");
        assertNotNull(versions.get(1).changedFieldCount(), "v1 基线变更字段数可算");

        CaseVersionDiff diff = caseService.diffVersion(info.id(), 2, 1);
        assertEquals(1, diff.fromVersion());
        assertEquals(2, diff.toVersion());
        var fields = diff.changes().stream().map(c -> c.field()).collect(Collectors.toSet());
        assertTrue(fields.contains("title"), "标题变更: " + fields);
        assertTrue(fields.contains("steps"), "步骤变更: " + fields);
        assertEquals("登录功能测试", changeMap(diff, "title").get("before"));
        assertEquals("登录功能测试V2", changeMap(diff, "title").get("after"));
        assertNotNull(changeMap(diff, "steps").get("before"));
    }

    @Test
    void diff缺省基准为当前版本() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "标题A"));
        caseService.update(1L, info.id(), new CaseUpdateInput("标题B", null, null,
                null, null, null, null, null, null, null), 1);

        CaseVersionDiff diff = caseService.diffVersion(info.id(), 1, null);
        assertEquals(2, diff.fromVersion());
        assertEquals(1, diff.toVersion());
        assertEquals("标题B", changeMap(diff, "title").get("before"));
        assertEquals("标题A", changeMap(diff, "title").get("after"));
    }

    @Test
    void 评审状态变更快照changeType2() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "评审快照"));
        caseService.review(1L, info.id(), ReviewAction.PASS, null, 1);

        List<CaseVersionInfo> versions = caseService.listVersions(info.id());
        assertEquals(2, versions.size());
        assertEquals(CaseVersionInfo.CHANGE_REVIEW, versions.get(0).changeType());
        var fields = caseService.diffVersion(info.id(), 2, 1).changes().stream()
                .map(c -> c.field()).collect(Collectors.toSet());
        assertEquals(java.util.Set.of("status"), fields, "评审仅状态字段变更: " + fields);
    }


    @Test
    void rollback_v3回滚到v1_生成v4内容等于v1_历史不篡改() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "标题V1"));
        String v1SnapshotJson = jdbcTemplate.queryForObject(
                "SELECT snapshot_json::text FROM tp_case_version WHERE case_id = ? AND version = 1",
                String.class, info.id());

        caseService.update(1L, info.id(), new CaseUpdateInput("标题V2", null, null,
                null, null, null, null, null, null, null), 1);
        caseService.update(1L, info.id(), new CaseUpdateInput("标题V3", null, null,
                null, null, null, null, null, null, null), 2);

        CaseRollbackResult result = caseService.rollback(2L, info.id(), 1, 3);
        assertEquals(4, result.newVersion(), "以旧版内容创建新版本 v4");
        assertEquals(1, result.rolledBackFrom());

        CaseInfo after = caseService.get(info.id());
        assertEquals("标题V1", after.title(), "内容=v1");
        assertEquals(4, after.version());

        List<CaseVersionInfo> versions = caseService.listVersions(info.id());
        assertEquals(4, versions.size());
        assertEquals(CaseVersionInfo.CHANGE_ROLLBACK, versions.get(0).changeType());
        assertEquals(2L, versions.get(0).operatorId());
        String v1After = jdbcTemplate.queryForObject(
                "SELECT snapshot_json::text FROM tp_case_version WHERE case_id = ? AND version = 1",
                String.class, info.id());
        assertEquals(v1SnapshotJson, v1After, "v1 历史快照原文不被篡改");
        Integer v3Title = jdbcTemplate.queryForObject(
                "SELECT (snapshot_json->>'title') FROM tp_case_version WHERE case_id = ? AND version = 3",
                String.class, info.id()) != null ? 1 : 0;
        assertEquals(1, v3Title, "v3 快照仍在");
        String v3 = jdbcTemplate.queryForObject(
                "SELECT snapshot_json->>'title' FROM tp_case_version WHERE case_id = ? AND version = 3",
                String.class, info.id());
        assertEquals("标题V3", v3);

        // Rollback audit row (02 contract Q1). R37 shared dev db: pin the audit
        // count to this case's object_id instead of counting every rollback row
        // in the database (factory rows and concurrent suites also contribute).
        Integer auditCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE object_type = 'case' AND action = 'rollback'"
                        + " AND object_id = ?",
                Integer.class, String.valueOf(info.id()));
        assertEquals(1, auditCount, "本用例回滚写审计 rollback");
    }

    @Test
    void rollback_恢复步骤内容() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "步骤回滚"));
        caseService.update(1L, info.id(), new CaseUpdateInput(null, null, null,
                null, null, null, null, steps("改过的步骤", "改过的断言"), null, null), 1);

        caseService.rollback(1L, info.id(), 1, 2);
        CaseInfo after = caseService.get(info.id());
        assertEquals("输入账号密码", after.steps().get(0).description(), "步骤随回滚恢复");
        String stepsText = jdbcTemplate.queryForObject(
                "SELECT steps_text FROM tp_test_case WHERE id = ?", String.class, info.id());
        assertTrue(stepsText.contains("输入账号密码"), "steps_text 聚合同步恢复: " + stepsText);
    }

    @Test
    void rollback_乐观锁冲突_40901() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "并发回滚"));
        caseService.update(1L, info.id(), new CaseUpdateInput("标题V2", null, null,
                null, null, null, null, null, null, null), 1);

        assertThrows(OptimisticLockingException.class,
                () -> caseService.rollback(2L, info.id(), 1, 1), "他人已提交 → 版本冲突");
    }


    @Test
    void rollback_目标版本不存在_404() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "边界"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> caseService.rollback(1L, info.id(), 9, 1));
        assertEquals(40401, e.getCode());
    }

    @Test
    void rollback_目标即当前版本_400人话() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "no-op回滚"));
        ServiceException e = assertThrows(ServiceException.class,
                () -> caseService.rollback(1L, info.id(), 1, 1));
        assertEquals(40001, e.getCode());
        assertTrue(e.getMessage().contains("当前版本"), e.getMessage());
    }

    @Test
    void 快照损坏_列表标记且diff回滚拒绝() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "损坏快照"));
        caseService.update(1L, info.id(), new CaseUpdateInput("标题V2", null, null,
                null, null, null, null, null, null, null), 1);
        jdbcTemplate.update("UPDATE tp_case_version SET snapshot_json = '123'::jsonb WHERE case_id = ? AND version = 2",
                info.id());

        List<CaseVersionInfo> versions = caseService.listVersions(info.id());
        assertTrue(versions.get(0).corrupted(), "损坏版本列表标记");
        assertThrows(ServiceException.class, () -> caseService.diffVersion(info.id(), 2, 1), "损坏版本 diff 拒绝");
        assertThrows(ServiceException.class, () -> caseService.rollback(1L, info.id(), 2, 2), "损坏版本回滚拒绝");
    }

    @Test
    void diff_跨度超20版本_truncated标记边界() {
        long pid = createProject("qv");
        CaseInfo info = caseService.create(1L, input(pid, "跨度用例"));
        for (int v = 1; v <= 22; v++) {
            caseService.update(1L, info.id(), new CaseUpdateInput("跨度用例V" + v, null, null,
                    null, null, null, null, null, null, null), v);
        }
        assertEquals(23, caseService.listVersions(info.id()).size());

        assertTrue(caseService.diffVersion(info.id(), 23, 1).truncated(), "跨度 22 > 20 应标记 truncated");
        assertTrue(caseService.diffVersion(info.id(), 23, 2).truncated(), "跨度 21 边界外应标记");
        assertFalse(caseService.diffVersion(info.id(), 23, 3).truncated(), "跨度恰 20 不标记（>20 才截断）");
        assertFalse(caseService.diffVersion(info.id(), 23, 22).truncated(), "相邻版本不标记");
    }

    @Test
    void 用例不存在_版本接口404() {
        assertThrows(ServiceException.class, () -> caseService.listVersions(999999L));
    }

    @Test
    void purge项目_版本快照随用例物理删除_零残留() {
        long pid = createProject("qp");
        CaseInfo info = caseService.create(1L, input(pid, "回滚用例"));
        caseService.update(1L, info.id(), new CaseUpdateInput("改过", null, null,
                null, null, null, null, null, null, null), 1);
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_version WHERE case_id = ?", Integer.class, info.id()));

        projectService.purge(1L, pid, true);
        Integer left = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_case_version WHERE case_id = ?", Integer.class, info.id());
        assertEquals(0, left, "purge 后版本快照必须物理删除（零残留）");
    }
}
