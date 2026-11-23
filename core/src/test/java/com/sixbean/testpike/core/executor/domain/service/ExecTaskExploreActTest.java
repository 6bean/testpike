package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.explore.api.controller.ExploreInternalController;
import com.sixbean.testpike.core.explore.api.dto.ExploreActInput;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExecTaskExploreActTest {

    private static final String ACT_JSON =
            "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\",\"url\":\"http://localhost:5173\"}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ExploreService exploreService;

    @Autowired
    private ExploreInternalController exploreController;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown : the explore-action name-family sweep
    // walks the FK graph (tp_explore_session/tp_explore_step reference
    // tp_project; tp_exec_task is in the helper's default project-keyed set),
    // replacing the blanket truncate that also wiped other suites' rows.
    // The executor drops by its exact registered name.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("探索动作");

    @BeforeEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name = 'ex-explore-act'");
    }

    private long newProject(String code) {
        return projectService.create(1L,
                new ProjectCreateInput("探索动作" + code, code, "zh-CN", "P1", null));
    }

    private long newSession(long projectId) {
        return exploreService.create(1L, projectId,
                new ExploreCreateInput("摸清下单流程", "http://localhost:5173"));
    }

    
    @Test
    void createExploreAct_落任务行_哨兵与类型与版本正确() {
        long projectId = newProject("EXP-ACT1");
        long taskId = execTaskService.createExploreAct(projectId, ACT_JSON);
        assertTrue(taskId > 0);

        var row = jdbcTemplate.queryForMap(
                "SELECT project_id, case_id, task_type, status, schema_version, env_base_url,"
                        + " action_json FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue());
        assertEquals(0L, ((Number) row.get("case_id")).longValue(), "case_id=0 哨兵（零 FK 承载）");
        assertEquals(3, ((Number) row.get("task_type")).intValue(), "探索动作必须 task_type=3");
        assertEquals(1, ((Number) row.get("status")).intValue(), "落库即待派发(1)");
        assertEquals("explore-v1", row.get("schema_version"),
                "探索动作信封 schema 版本（tool-v1 同族先例）");
        assertNull(row.get("env_base_url"), "探索动作无环境语境（URL 在信封内）");
        assertEquals(ACT_JSON, row.get("action_json"));
    }

    
    @Test
    void createExploreAct_空参拒40001_不落库() {
        long projectId = newProject("EXP-ACT2");
        ServiceException noProject = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(0L, ACT_JSON));
        assertEquals(40001, noProject.getCode().intValue());

        ServiceException blankAct = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(projectId, "  "));
        assertEquals(40001, blankAct.getCode().intValue());
        assertTrue(blankAct.getMessage().contains("不能为空"), "人话: " + blankAct.getMessage());

        long rows = FixtureTaskCounters.countInFamily(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        assertEquals(0L, rows);
    }

    
    @Test
    void claim_探索任务可领取_taskType3信封下发() {
        long projectId = newProject("EXP-ACT3");
        long taskId = execTaskService.createExploreAct(projectId, ACT_JSON);
        long executorId = executorService.register(new ExecutorRegisterInput(
                "ex-explore-act", null, null, "web", "chromium", 2, "v1", 0));

        ClaimTaskView view = execTaskService.claim(executorId);

        assertNotNull(view, "探索任务必须能经 claim 通道领取（红线6 零新通道）");
        assertEquals(taskId, view.taskId());
        assertEquals(3, view.taskType(), "探索任务必须下发 taskType=3");
        assertTrue(view.actionJson().contains("explore-act"), "actionJson 必须携带动作信封");
        assertNull(view.envBaseUrl(), "无环境语境下发 NULL");
        assertEquals(0L, view.caseId(), "case_id=0 哨兵下发");
    }

    
    @Test
    void acts端点_运行中会话_任务落库项目取会话归属() {
        long projectId = newProject("EXP-ACT4");
        long sessionId = newSession(projectId);

        var resp = exploreController.submitAct(sessionId, new ExploreActInput(ACT_JSON));

        assertNotNull(resp.getData());
        long taskId = resp.getData();
        var row = jdbcTemplate.queryForMap(
                "SELECT project_id, task_type FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(projectId, ((Number) row.get("project_id")).longValue(),
                "projectId 必须取会话归属（内核侧不传项目）");
        assertEquals(3, ((Number) row.get("task_type")).intValue());
    }

    
    @Test
    void acts端点_终态拒40902_缺失拒40401() {
        long projectId = newProject("EXP-ACT5");
        long sessionId = newSession(projectId);
        exploreService.updateStatus(sessionId, 2);

        ServiceException terminal = assertThrows(ServiceException.class,
                () -> exploreController.submitAct(sessionId, new ExploreActInput(ACT_JSON)));
        assertEquals(40902, terminal.getCode().intValue(), "终态封转动作提交拒: " + terminal.getMessage());
        assertTrue(terminal.getMessage().contains("终态"), "人话含「终态」: " + terminal.getMessage());

        ServiceException missing = assertThrows(ServiceException.class,
                () -> exploreController.submitAct(9999999L, new ExploreActInput(ACT_JSON)));
        assertEquals(40401, missing.getCode().intValue());

        long rows = FixtureTaskCounters.countInFamily(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        assertEquals(0L, rows, "守门拒后不得落任务");
    }


    private static final String MOBILE_BASE =
            "{\"kind\":\"explore-act\",\"target\":\"mobile\",\"action\":\"tree-snapshot\"";

    
    private static String mobileEnvelope(String action, String engine, String serial) {
        return MOBILE_BASE + (action == null ? "" : ",\"action\":\"" + action + "\"")
                + (engine == null ? "" : ",\"engine\":" + engine)
                + (serial == null ? ",\"leaseRef\":null"
                        : ",\"leaseRef\":{\"serial\":\"" + serial + "\"}") + "}";
    }

    
    @Test
    void createExploreAct_移动信封合法形状_落task3() {
        long projectId = newProject("EXP-MOB1");
        long taskId = execTaskService.createExploreAct(projectId,
                mobileEnvelope("tree-snapshot", "{\"id\":\"mobilewright\"}", "emulator-5554"));
        var row = jdbcTemplate.queryForMap(
                "SELECT task_type, schema_version FROM tp_exec_task WHERE id = ?", taskId);
        assertEquals(3, ((Number) row.get("task_type")).intValue());
        assertEquals("explore-v1", row.get("schema_version"), "移动形态复用同一 schema 版本（task_type=3 通道）");
    }

    
    @Test
    void createExploreAct_移动信封缺engine拒40001_不落库() {
        long projectId = newProject("EXP-MOB2");
        ServiceException noEngine = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(projectId,
                        mobileEnvelope("tap", null, "emulator-5554")));
        assertEquals(40001, noEngine.getCode().intValue());
        assertTrue(noEngine.getMessage().contains("engine"), "人话点名 engine: " + noEngine.getMessage());

        ServiceException emptyEngine = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(projectId,
                        mobileEnvelope("tap", "{}", "emulator-5554")));
        assertEquals(40001, emptyEngine.getCode().intValue());

        long rows = FixtureTaskCounters.countInFamily(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        assertEquals(0L, rows, "形状拒零残留");
    }

    
    @Test
    void createExploreAct_移动信封缺serial拒40001() {
        long projectId = newProject("EXP-MOB3");
        ServiceException noSerial = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(projectId,
                        mobileEnvelope("tap", "{\"id\":\"mobilewright\"}", "  ")));
        assertEquals(40001, noSerial.getCode().intValue());
        assertTrue(noSerial.getMessage().contains("serial"), "人话点名 serial: " + noSerial.getMessage());

        long rows = FixtureTaskCounters.countInFamily(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        assertEquals(0L, rows);
    }

 
    @Test
    void createExploreAct_移动信封黑名单动词拒40001() {
        long projectId = newProject("EXP-MOB4");
        ServiceException forbidden = assertThrows(ServiceException.class,
                () -> execTaskService.createExploreAct(projectId,
                        mobileEnvelope("uninstall", "{\"id\":\"mobilewright\"}", "emulator-5554")));
        assertEquals(40001, forbidden.getCode().intValue());
        assertTrue(forbidden.getMessage().contains("黑名单"),
                "人话点名探索模式黑名单: " + forbidden.getMessage());

        long rows = FixtureTaskCounters.countInFamily(jdbcTemplate, FIXTURE_PROJECT_NAMES);
        assertEquals(0L, rows);
    }
}
