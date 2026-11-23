package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class BatchCleanFlagWriteTest {

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // appauto dispatch suite): this suite's projects live in the name family
    // below and the FK child-first sweep walks everything keyed off them
    // (tp_test_case/tp_user_project under tp_project, tp_exec_task/tp_exec_batch
    // among the project-keyed defaults). tp_batch_case carries no FK and no
    // project column, so this family's rows purge first through the batch rows
    // that still exist at that point; tp_batch_template is project-keyed
    // without an FK into tp_project, so it goes through extraProjectTables.
    // The old CASCADE wiped tp_user rows the shipped baseline owns — the sweep
    // never touches them.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目CLEAN";

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> familyIds =
                com.sixbean.testpike.core.support.SharedDbFixtureSweep.projectIdsByNamePrefixes(
                        jdbcTemplate, List.of(FIXTURE_PROJECT_NAME_PREFIX));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, familyIds);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of("tp_batch_template"),
                List.of(FIXTURE_PROJECT_NAME_PREFIX));
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        return info.id();
    }

    @Test
    void create_开清机旗标_回显并落库() {
        long pid = createProject("CLEAN1");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "清机批", null,
                List.of(caseId), null, null, null, null, 1));

        assertEquals(1, batch.cleanAppData(), "创建响应回显清机旗标");
        Integer stored = jdbcTemplate.queryForObject(
                "SELECT clean_app_data FROM tp_exec_batch WHERE id = ?", Integer.class, batch.id());
        assertEquals(1, stored, "clean_app_data 真实落库");
    }

    @Test
    void create_旧构造缺省_旗标关零误清() {
        long pid = createProject("CLEAN2");
        long caseId = createCase(pid, "用例1");

        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "缺省批", null,
                List.of(caseId), null));

        assertEquals(0, batch.cleanAppData(), "旧构造（未传旗标）=0=存量零误清");
    }

    @Test
    void create_旗标非法值_400人话() {
        long pid = createProject("CLEAN3");
        long caseId = createCase(pid, "用例1");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.create(1L, new BatchCreateInput(pid, "非法批", null,
                        List.of(caseId), null, null, null, null, 2)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("清机"), "人话定位清机字段: " + ex.getMessage());
    }

    @Test
    void update_草稿编辑旗标_落库且null不改() {
        long pid = createProject("CLEAN4");
        long caseId = createCase(pid, "用例1");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "编辑批", null,
                List.of(caseId), null));
        assertEquals(0, batch.cleanAppData(), "建批缺省关");

        batchService.update(1L, batch.id(), new BatchUpdateInput(null, null, null, null,
                null, null, null, 1));
        assertEquals(1, batchService.get(batch.id()).cleanAppData(), "编辑开旗标生效落库");

        batchService.update(1L, batch.id(), new BatchUpdateInput("改名", null, null, null));
        assertEquals(1, batchService.get(batch.id()).cleanAppData(), "null=不改既有旗标");

        batchService.update(1L, batch.id(), new BatchUpdateInput(null, null, null, null,
                null, null, null, 0));
        assertEquals(0, batchService.get(batch.id()).cleanAppData(), "编辑关旗标生效");
    }

    @Test
    void update_旗标非法值_400人话() {
        long pid = createProject("CLEAN5");
        long caseId = createCase(pid, "用例1");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "非法编辑批", null,
                List.of(caseId), null));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.update(1L, batch.id(), new BatchUpdateInput(null, null, null,
                        null, null, null, null, 5)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("清机"), "人话: " + ex.getMessage());
    }

    @Test
    void saveAsTemplate_模板继承源批旗标() {
        long pid = createProject("CLEAN6");
        long caseId = createCase(pid, "用例1");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "母批", null,
                List.of(caseId), null, null, null, null, 1));

        BatchTemplateInfo tpl = batchService.saveAsTemplate(1L, batch.id(), "清机模板");

        assertEquals(1, tpl.cleanAppData(), "模板继承源批清机旗标（模板默认位随复用建批落批行）");
    }
}
