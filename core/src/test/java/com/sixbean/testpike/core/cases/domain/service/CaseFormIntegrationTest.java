package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseFormIntegrationTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> formTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep purges this suite's cases, steps, tag links, tag dictionary
        // rows and audit trail.
        ProjectFixtureRig.sweep(jdbcTemplate, formTrackedProjects);
        formTrackedProjects.clear();
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                formTrackedProjects, "项目" + code, code);
    }

    private long createTag(long projectId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, ?, 4) RETURNING id
                """, Long.class, projectId, name);
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)))).id();
    }

    private List<Long> tagIdsOf(long caseId) {
        return jdbcTemplate.queryForList("SELECT tag_id FROM tp_case_tag WHERE case_id = ?", Long.class, caseId);
    }

    private Long assigneeOf(long caseId) {
        return jdbcTemplate.queryForObject("SELECT assignee_id FROM tp_test_case WHERE id = ?", Long.class, caseId);
    }

    @Test
    void create_带标签和分配人_标签绑定分配人落库审计存在() {
        long pid = createProject("nf1");
        long t1 = createTag(pid, "冒烟");
        long t2 = createTag(pid, "回归");
        CaseTestMembers.addMember(jdbcTemplate, pid, 77); // tester

        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "表单新建", "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)), List.of(t1, t2), 77L));

        assertEquals(List.of(t1, t2), tagIdsOf(info.id()), "TC-N7-01：新建应绑定标签");
        assertEquals(77L, assigneeOf(info.id()), "TC-N7-01：新建应落分配人");
        // Per-object exact count (shared dev DB): the run's own case carries
        // exactly one create audit row; global scans only bound below.
        Long audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'create' AND object_type = 'case' AND object_id = ?",
                Long.class, String.valueOf(info.id()));
        assertEquals(1, audit, "TC-N7-01：新建应产生审计");
    }

    @Test
    void update_改标签和分配人_覆盖式更新版本递增() {
        long pid = createProject("nf2");
        long cid = createCase(pid, "待编辑");
        long t1 = createTag(pid, "旧标签");
        long t2 = createTag(pid, "新标签");
        CaseTestMembers.addMember(jdbcTemplate, pid, 77);

        CaseInfo before = caseService.update(1L, cid,
                new CaseUpdateInput(null, null, null, null, null, null, null, null, List.of(t1), 77L), 1);
        assertEquals(2, before.version(), "version 应递增到 2");
        assertEquals(List.of(t1), tagIdsOf(cid));
        assertEquals(77L, assigneeOf(cid));

        CaseTestMembers.addMember(jdbcTemplate, pid, 88);
        CaseInfo after = caseService.update(1L, cid,
                new CaseUpdateInput(null, null, null, null, null, null, null, null, List.of(t2), 88L), before.version());

        assertEquals(List.of(t2), tagIdsOf(cid), "TC-N7-02：标签应覆盖为仅新标签");
        assertEquals(88L, assigneeOf(cid), "TC-N7-02：分配人应更新");
        assertEquals(before.version() + 1, after.version(), "version 应继续递增");
    }

    @Test
    void create_标签跨项目_整体400人话() {
        long pidA = createProject("nfa");
        long pidB = createProject("nfb");
        long otherTag = createTag(pidB, "他项目标签");

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.create(1L,
                new CaseCreateInput(pidA, "跨项目标签", "前置", "预期", 2, 2, "web", 1, 1, 1, null,
                        List.of(new CaseStepInput(1, "步骤1", 0)), List.of(otherTag), null)));

        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "IT-N7-01：跨项目标签应 400");
        assertTrue(ex.getMessage().contains("项目"), "人话应含项目隔离说明: " + ex.getMessage());
    }

    @Test
    void create_分配非项目成员_403人话() {
        long pid = createProject("nfc");
        CaseTestMembers.ensureUserWithRole(jdbcTemplate, 66, "tester");

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.create(1L,
                new CaseCreateInput(pid, "分配非成员", "前置", "预期", 2, 2, "web", 1, 1, 1, null,
                        List.of(new CaseStepInput(1, "步骤1", 0)), null, 66L)));

        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "IT-N7-02：非成员分配应 403");
        assertTrue(ex.getMessage().contains("成员"), "人话应含成员说明: " + ex.getMessage());
    }

    @Test
    void create_分配开发人员_403人话() {
        long pid = createProject("nfd");
        CaseTestMembers.addMemberWithRole(jdbcTemplate, pid, 66, "developer");

        ServiceException ex = assertThrows(ServiceException.class, () -> caseService.create(1L,
                new CaseCreateInput(pid, "分配开发", "前置", "预期", 2, 2, "web", 1, 1, 1, null,
                        List.of(new CaseStepInput(1, "步骤1", 0)), null, 66L)));

        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "IT-N7-02：开发角色分配应 403");
        assertTrue(ex.getMessage().contains("测试人员"), "人话应含测试人员说明: " + ex.getMessage());
    }

    @Test
    void update_null语义_标签null不更新空列表清空分配人0清空() {
        long pid = createProject("nfe");
        long cid = createCase(pid, "语义用例");
        long t1 = createTag(pid, "标签甲");
        CaseTestMembers.addMember(jdbcTemplate, pid, 77);

        caseService.update(1L, cid,
                new CaseUpdateInput(null, null, null, null, null, null, null, null, List.of(t1), 77L), 1);
        assertEquals(List.of(t1), tagIdsOf(cid));
        assertEquals(77L, assigneeOf(cid));

        CaseInfo noChange = caseService.update(1L, cid,
                new CaseUpdateInput(null, null, null, null, null, null, null, null, null, null), 2);
        assertEquals(List.of(t1), tagIdsOf(cid), "tagIds null 不应更新标签");
        assertEquals(77L, assigneeOf(cid), "assigneeId null 不应更新分配人");
        assertEquals(3, noChange.version(), "version 仍递增（字段级更新语义）");

        CaseInfo cleared = caseService.update(1L, cid,
                new CaseUpdateInput(null, null, null, null, null, null, null, null, List.of(), 0L), 3);
        assertTrue(tagIdsOf(cid).isEmpty(), "空列表应清空标签");
        assertNull(assigneeOf(cid), "assigneeId=0 应清空分配人");
        assertEquals(4, cleared.version());
    }
}
