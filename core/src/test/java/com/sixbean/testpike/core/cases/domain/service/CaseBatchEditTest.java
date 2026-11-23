package com.sixbean.testpike.core.cases.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.BatchEditFailureItem;
import com.sixbean.testpike.core.cases.api.dto.BatchEditRequest;
import com.sixbean.testpike.core.cases.api.dto.BatchEditResult;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class CaseBatchEditTest {

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final java.util.List<Long> batchEditTrackedProjects =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Shared dev database (R37): no blanket table wipe — the sweep covers the
        // fixture projects' cases, tag links, tag dictionary rows and audit trail.
        ProjectFixtureRig.sweep(jdbcTemplate, batchEditTrackedProjects);
    }

    private long createProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                batchEditTrackedProjects, "项目" + code, code);
    }

    private long createCase(long projectId, String title) {
        return caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                2, 2, "web", 1, 1, 1, null,
                List.of(new CaseStepInput(1, "步骤1", 0)))).id();
    }

    private long createTag(long projectId, String name) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_tag_dict (project_id, name, type) VALUES (?, ?, 4) RETURNING id
                """, Long.class, projectId, name);
    }

    private Integer phaseOf(long caseId) {
        return jdbcTemplate.queryForObject("SELECT phase FROM tp_test_case WHERE id = ?", Integer.class, caseId);
    }

    private Integer priorityOf(long caseId) {
        return jdbcTemplate.queryForObject("SELECT priority FROM tp_test_case WHERE id = ?", Integer.class, caseId);
    }

    private List<Long> tagIdsOf(long caseId) {
        return jdbcTemplate.queryForList("SELECT tag_id FROM tp_case_tag WHERE case_id = ?", Long.class, caseId);
    }

    @Test
    void batchEdit_改阶段优先级标签_结果报告与审计() {
        long pid = createProject("be1");
        long c1 = createCase(pid, "批量1");
        long c2 = createCase(pid, "批量2");
        long t1 = createTag(pid, "标签甲");
        long t2 = createTag(pid, "标签乙");
        // Watermark before the call: the shared dev database also carries other
        // suites' audit rows, so the assertion counts only this run's inserts.
        long auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");

        BatchEditResult result = caseService.batchEdit(1L, new BatchEditRequest(
                pid, List.of(c1, c2), 2, 1, List.of(t1, t2)));

        assertEquals(List.of(c1, c2), result.succeeded(), "两条用例均应成功");
        assertTrue(result.failures().isEmpty(), "不应有失败项");
        assertEquals(2, phaseOf(c1), "阶段应改为 2=功能");
        assertEquals(1, priorityOf(c1), "优先级应改为 1=P1");
        assertEquals(List.of(t1, t2), tagIdsOf(c1), "标签应覆盖为 甲+乙");
        assertEquals(2, phaseOf(c2));
        assertEquals(List.of(t1, t2), tagIdsOf(c2));
        Long audit = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'case_batch_edit' AND id > ?",
                Long.class, auditFloor);
        assertEquals(1, audit, "TC-N4-01：批量编辑应产生审计");
    }

    @Test
    void batchEdit_混入他项目用例_逐条失败H1隔离() {
        long pidA = createProject("bea");
        long pidB = createProject("beb");
        long cA1 = createCase(pidA, "A1");
        long cA2 = createCase(pidA, "A2");
        long cB = createCase(pidB, "B1");

        BatchEditResult result = caseService.batchEdit(1L, new BatchEditRequest(
                pidA, List.of(cA1, cB, cA2), 3, null, null));

        assertEquals(List.of(cA1, cA2), result.succeeded(), "本项目用例应成功");
        assertEquals(1, result.failures().size(), "他项目用例应失败");
        BatchEditFailureItem failure = result.failures().get(0);
        assertEquals(cB, failure.caseId(), "IT-N4-01：失败项=他项目用例");
        assertTrue(failure.reason().contains("项目"), "失败原因应为人话含项目隔离说明: " + failure.reason());
        assertEquals(1, phaseOf(cB), "他项目用例不应被改动（H1 隔离）");
    }

    @Test
    void batchEdit_不存在的用例_跳过并列入失败() {
        long pid = createProject("ben");
        long c1 = createCase(pid, "存在");

        BatchEditResult result = caseService.batchEdit(1L, new BatchEditRequest(
                pid, List.of(c1, 999999L), 1, null, null));

        assertEquals(List.of(c1), result.succeeded());
        assertEquals(1, result.failures().size());
        assertTrue(result.failures().get(0).reason().contains("不存在"), "不存在应有人话原因");
    }

    @Test
    void batchEdit_参数非法_整体拒绝() {
        long pid = createProject("bev");
        long c1 = createCase(pid, "参数");
        ServiceException ex1 = assertThrows(ServiceException.class,
                () -> caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(), 1, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex1.getCode(), "空 caseIds 应 400");
        ServiceException ex2 = assertThrows(ServiceException.class,
                () -> caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), null, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex2.getCode(), "无编辑字段应 400");
        ServiceException ex3 = assertThrows(ServiceException.class,
                () -> caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), 9, null, null)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex3.getCode(), "非法阶段应 400");
        long otherPid = createProject("beo");
        long otherTag = createTag(otherPid, "他项目标签");
        ServiceException ex4 = assertThrows(ServiceException.class,
                () -> caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), null, null, List.of(otherTag))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex4.getCode(), "跨项目标签应 400");
    }

    @Test
    void batchEdit_部分字段_只更新非空字段() {
        long pid = createProject("bep");
        long c1 = createCase(pid, "部分字段");

        caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), 4, null, null));

        assertEquals(4, phaseOf(c1), "仅阶段更新");
        assertEquals(2, priorityOf(c1), "优先级保持原值（null 不更新）");
    }

    @Test
    void batchEdit_清空标签_传空列表() {
        long pid = createProject("bet");
        long c1 = createCase(pid, "清标签");
        long t1 = createTag(pid, "待清");
        caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), null, null, List.of(t1)));
        assertEquals(List.of(t1), tagIdsOf(c1), "先打上标签");

        caseService.batchEdit(1L, new BatchEditRequest(pid, List.of(c1), null, null, List.of()));

        assertTrue(tagIdsOf(c1).isEmpty(), "空列表=清空标签（覆盖式语义）");
    }

    
    private CaseInfo info(long caseId) {
        return caseService.get(caseId);
    }
}
