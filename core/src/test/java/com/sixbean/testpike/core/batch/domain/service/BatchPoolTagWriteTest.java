package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.domain.service.TagService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
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
class BatchPoolTagWriteTest {

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private TagService tagService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
    // this suite's projects live in the name family below (codes POOL*). The
    // FK child-first sweep covers tp_test_case/tp_user_project under
    // tp_project and tp_exec_batch among the project-keyed defaults;
    // tp_batch_case carries no FK and no project column, so this family's
    // rows purge first through the batch rows still present; tp_tag_dict is
    // project-keyed WITHOUT an FK into tp_project, so it rides
    // extraProjectTables. The old CASCADE wiped shipped tp_user rows and
    // every other suite's tags/projects — the sweep leaves them alone.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目POOL";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, List.of(FIXTURE_PROJECT_NAME_PREFIX));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of("tp_tag_dict"), List.of(FIXTURE_PROJECT_NAME_PREFIX));
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        return info.id();
    }

    private long createPoolTag(long projectId, String name) {
        return tagService.create(1L, new TagCreateInput(projectId, name, 7)).id();
    }

    @Test
    void create_挂池批次_回显并落库() {
        long pid = createProject("POOL1");
        long tagId = createPoolTag(pid, "回归池");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "挂池批", null,
                List.of(caseId), null, null, tagId));

        assertEquals(tagId, batch.poolTagId(), "创建响应回显资源池");
        Long stored = jdbcTemplate.queryForObject(
                "SELECT pool_tag_id FROM tp_exec_batch WHERE id = ?", Long.class, batch.id());
        assertEquals(tagId, stored, "pool_tag_id 真实落库");
    }

    @Test
    void create_未挂池_零变化() {
        long pid = createProject("POOL2");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "无池批", null,
                List.of(caseId), null));

        assertNull(batch.poolTagId(), "旧构造（未选池）恒 null=存量零变化");
    }

    @Test
    void create_池标签非池类型_400人话() {
        long pid = createProject("POOL3");
        long normalTag = tagService.create(1L, new TagCreateInput(pid, "普通标签", 1)).id();
        long caseId = createCase(pid, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pid, "错型池批", null,
                        List.of(caseId), null, null, normalTag)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("资源池"), "人话定位资源池字段: " + ex.getMessage());
    }

    @Test
    void create_池标签跨项目_400人话() {
        long pidA = createProject("POOLA");
        long pidB = createProject("POOLB");
        long tagB = createPoolTag(pidB, "B项目池");
        long caseId = createCase(pidA, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pidA, "跨项目池批", null,
                        List.of(caseId), null, null, tagB)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不属于该项目"), ex.getMessage());
    }

    @Test
    void create_池标签不存在_400人话() {
        long pid = createProject("POOL4");
        long caseId = createCase(pid, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pid, "悬空池批", null,
                        List.of(caseId), null, null, 999999L)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void update_草稿改池生效_null不改() {
        long pid = createProject("POOL5");
        long tag1 = createPoolTag(pid, "池一");
        long tag2 = createPoolTag(pid, "池二");
        long caseId = createCase(pid, "用例1");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "改池批", null,
                List.of(caseId), null, null, tag1));

        BatchInfo untouched = batchService.update(1L, batch.id(),
                new BatchUpdateInput("改名", null, null, null, null, null));
        assertEquals(tag1, untouched.poolTagId(), "poolTagId=null 不改既有池");
        assertEquals("改名", untouched.name());

        BatchInfo changed = batchService.update(1L, batch.id(),
                new BatchUpdateInput(null, null, null, null, null, tag2));
        assertEquals(tag2, changed.poolTagId(), "编辑改池生效并回显");
        Long stored = jdbcTemplate.queryForObject(
                "SELECT pool_tag_id FROM tp_exec_batch WHERE id = ?", Long.class, batch.id());
        assertEquals(tag2, stored, "改池真实落库");
    }

    @Test
    void update_挂错型池_400() {
        long pid = createProject("POOL6");
        long caseId = createCase(pid, "用例1");
        long normalTag = tagService.create(1L, new TagCreateInput(pid, "普通标签", 2)).id();
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "错型改池批", null,
                List.of(caseId), null));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.update(1L, batch.id(),
                        new BatchUpdateInput(null, null, null, null, null, normalTag)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }
}
