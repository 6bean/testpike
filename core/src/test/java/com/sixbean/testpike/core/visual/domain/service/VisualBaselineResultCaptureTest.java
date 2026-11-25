package com.sixbean.testpike.core.visual.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.component.VisualBaselineStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;


@SpringBootTest(classes = CoreApplication.class)
class VisualBaselineResultCaptureTest {    /** Suite-exclusive executor seat prefix (fresh-install determinism). */
    private static final String EXEC_PREFIX = "tpf-visual-cap-";
    private com.sixbean.testpike.core.support.FleetSeatFixture fleetSeat;


    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private VisualBaselineService visualBaselineService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** Fixture projects of this suite; the teardown sweeps only these rows. */
    private final List<Long> captureProjects = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Executor registrations of this suite; dropped by id (no global wipe). */
    private final List<Long> captureExecutors = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fresh-install determinism: this suite routes on the shared fleet
        // registry, so it brings its own engine-capable seat and quiesces the
        // rest of the online view for the window (single-source fixture, R40).
        fleetSeat = com.sixbean.testpike.core.support.FleetSeatFixture.open(jdbcTemplate, EXEC_PREFIX, "GATLING", "K6");

        // Shared dev database (R37): no blanket table wipe — the tracked-project
        // FK sweep purges this suite's cases, tasks, envs and audit rows;
        // tp_visual_baseline carries project_id without an FK, so it is named
        // as an extra table; executor registrations drop by their tracked ids.
        // Crash-run leftovers of this suite share the name prefix, so the
        // name-family sweep clears them before the tracked sweep runs.
        SharedDbFixtureSweep.byProjectNameFamily(jdbcTemplate, List.of("tp_visual_baseline"),
                List.of("候选落库测试"));
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, List.of("tp_visual_baseline"), captureProjects);
        captureProjects.clear();
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_executor", captureExecutors);
        captureExecutors.clear();
    
        if (fleetSeat != null) {
            fleetSeat.close();
            fleetSeat = null;
        }
}

    private long createProject() {
        String code = "VR" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return ProjectFixtureRig.create(jdbcTemplate, projectService, 1L, captureProjects,
                "候选落库测试" + code, code);
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long projectId) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "候选落库用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    
    private ClaimTaskView claimTask(long projectId, long caseId, long envId, String actionJson) {
        execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2", actionJson, envId));
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-capture-" + UUID.randomUUID().toString().substring(0, 6),
                null, null, "web", "chromium", 2, "1.0.0", 0));
        captureExecutors.add(exId);
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "待派发任务必须可领取");
        return view;
    }

    private String stepKey(long caseId, String name) {
        return "case:" + caseId + ":step:1:" + name;
    }

    private void submit(ClaimTaskView view, List<Map<String, Object>> timeline) {
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "pass", null, null, timeline, null, 1234, null));
    }

    private static final String VISUAL_ACTION_JSON_TEMPLATE = """
            {"version":"v2","steps":[{"action":"visual.assert","name":"%s"%s}]}""";

    private String actionJson(String name, Double tolerance) {
        return String.format(VISUAL_ACTION_JSON_TEMPLATE, name,
                tolerance == null ? "" : ",\"tolerance\":" + tolerance);
    }

    @Test
    void baseline_pending行带回传_落pending候选_容差步值透传() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson("home", 0.03));
        String artifactKey = "testpike/9001/exec-artifact/202609/ab12cd34-candidate_1.png";
        submit(view, List.of(Map.of("stepNo", 1, "action", "visual.assert",
                "status", "baseline_pending", "stepKey", stepKey(caseId, "home"),
                "artifactKey", artifactKey,
                "startAt", "2026-09-27T07:00:00+08:00", "endAt", "2026-09-27T07:00:01+08:00")));

        VisualBaselineInfo candidate = visualBaselineService.findConfirmed(pid, stepKey(caseId, "home"));
        assertNull(candidate, "未确认前不生效基线");
        List<VisualBaselineInfo> chain = visualBaselineService.listByStep(pid, stepKey(caseId, "home"));
        assertEquals(1, chain.size(), "回传一条 pending 候选");
        VisualBaselineInfo row = chain.get(0);
        assertEquals(VisualBaselineStatus.PENDING, row.status());
        assertEquals(artifactKey, row.sourceArtifactKey(), "来源=runner 回写 artifactKey");
        assertEquals(view.taskId(), row.sourceTaskId(), "来源任务 id 对账");
        assertEquals(0.03, row.diffTolerance(), "容差=步 tolerance 透传");
    }

    @Test
    void 未确认期重跑再回传_覆盖来源不新建行() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView first = claimTask(pid, caseId, envId, actionJson("home", null));
        submit(first, List.of(Map.of("stepNo", 1, "action", "visual.assert",
                "status", "baseline_pending", "stepKey", stepKey(caseId, "home"),
                "artifactKey", "testpike/9001/exec-artifact/202609/aa11bb22-candidate_1.png")));
        Long rowId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid);

        ClaimTaskView second = claimTask(pid, caseId, envId, actionJson("home", null));
        submit(second, List.of(Map.of("stepNo", 1, "action", "visual.assert",
                "status", "baseline_pending", "stepKey", stepKey(caseId, "home"),
                "artifactKey", "testpike/9001/exec-artifact/202609/cc33dd44-candidate_1.png")));

        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid),
                "未确认期重采=覆盖不新建行");
        VisualBaselineInfo row = visualBaselineService.getById(rowId);
        assertEquals("testpike/9001/exec-artifact/202609/cc33dd44-candidate_1.png",
                row.sourceArtifactKey(), "来源推进到最新采集");
        assertEquals(second.taskId(), row.sourceTaskId());
    }

    @Test
    void 缺artifactKey或stepKey的pending行跳过_零空来源登记() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson("home", null));
        submit(view, List.of(
                Map.of("stepNo", 1, "action", "visual.assert",
                        "status", "baseline_pending", "stepKey", stepKey(caseId, "home")),
                Map.of("stepNo", 2, "action", "visual.assert",
                        "status", "baseline_pending",
                        "artifactKey", "testpike/9001/exec-artifact/202609/ee55ff66-candidate_2.png")));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid));
    }

    @Test
    void fail行带对比图artifactKey_不落候选() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson("home", 0.01));
        execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                "fail", 1, "视觉断言不满足：差异像素占比 5.0000% 超出容差 1.0000%",
                List.of(Map.of("stepNo", 1, "action", "visual.assert",
                        "status", "fail", "stepKey", stepKey(caseId, "home"),
                        "artifactKey", "testpike/9001/exec-artifact/202609/ff77aa88-visual-diff_1.png")),
                null, 2222, null));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid));
    }

    @Test
    void 无visual步任务回传_零副作用_步无容差候选容差空() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView noise = claimTask(pid, caseId, envId,
                "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local\"}}]}");
        submit(noise, List.of(Map.of("stepNo", 1, "action", "goto", "status", "ok")));
        assertEquals(0L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid));

        long caseId2 = caseService.create(1L, new CaseCreateInput(pid, "候选落库用例二",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of())).id();
        ClaimTaskView noTol = claimTask(pid, caseId2, envId, actionJson("p", null));
        submit(noTol, List.of(Map.of("stepNo", 1, "action", "visual.assert",
                "status", "baseline_pending", "stepKey", stepKey(caseId2, "p"),
                "artifactKey", "testpike/9001/exec-artifact/202609/ab99cd00-candidate_1.png")));
        List<VisualBaselineInfo> chain = visualBaselineService.listByStep(pid, stepKey(caseId2, "p"));
        assertEquals(1, chain.size());
        assertNull(chain.get(0).diffTolerance());
    }

    @Test
    void 终态任务重复回传幂等_不二次落候选() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson("home", null));
        List<Map<String, Object>> timeline = List.of(Map.of("stepNo", 1, "action", "visual.assert",
                "status", "baseline_pending", "stepKey", stepKey(caseId, "home"),
                "artifactKey", "testpike/9001/exec-artifact/202609/12ab34cd-candidate_1.png"));
        submit(view, timeline);
        submit(view, timeline);
        assertEquals(1L, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_visual_baseline WHERE project_id = ?", Long.class, pid));
    }
}
