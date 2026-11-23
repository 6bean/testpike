package com.sixbean.testpike.core.explore.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.controller.ExploreInternalController;
import com.sixbean.testpike.core.explore.api.dto.ExploreStandaloneActInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExploreStandaloneActTest {

    private static final String ACT_JSON =
            "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"url\":\"http://localhost:8899/login\"}";

    @Autowired
    private ExploreInternalController exploreController;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
 * R37 fixture-scoped teardown : the suite owns the
 * SA1/SA2 fixture projects and everything hung off them — the explore act
 * task is project-keyed (tp_exec_task is in the sweep's default set) and
 * its step snapshots cascade with it. The family is resolved from the
 * database, so a crashed run's residue is cleared before the next test.
 * The old blanket TRUNCATE wiped shipped projects and other suites'
 * executor rows on the shared dev database; this suite creates none.
 */
    @BeforeEach
    @AfterEach
    void cleanFixture() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code IN ('SA1','SA2')", Long.class));
    }

    private long newProject(String code) {
        return projectService.create(1L,
                new ProjectCreateInput("探查提交" + code, code, "zh-CN", "P1", null));
    }

    
    @Test
    void acts端点_落探索动作哨兵行() {
        long projectId = newProject("SA1");

        Long taskId = exploreController.standaloneAct(
                new ExploreStandaloneActInput(projectId, ACT_JSON)).getData();

        assertTrue(taskId != null && taskId > 0, "返回落库任务 id");
        var row = jdbcTemplate.queryForMap(
                "SELECT project_id, case_id, task_type, status, schema_version, action_json"
                        + " FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        assertEquals(0L, ((Number) row.get("case_id")).longValue(), "case_id=0 哨兵");
        assertEquals(3, ((Number) row.get("task_type")).intValue(), "必须 task_type=3（explore_worker 唯一入口）");
        assertEquals("explore-v1", row.get("schema_version"));
        assertEquals(ACT_JSON, row.get("action_json"), "信封原样落库（core 不解析语义）");
    }

    
    @Test
    void acts端点_空动作JSON_40001人话() {
        long projectId = newProject("SA2");

        ServiceException ex = assertThrows(ServiceException.class,
                () -> exploreController.standaloneAct(new ExploreStandaloneActInput(projectId, "  ")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    
    @Test
    void acts端点_项目非法_40001人话() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> exploreController.standaloneAct(new ExploreStandaloneActInput(0L, ACT_JSON)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }
}
