package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineCandidateInput;
import com.sixbean.testpike.core.visual.api.dto.VisualBaselineInfo;
import com.sixbean.testpike.core.visual.domain.service.VisualBaselineService;
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
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class VisualBaselineClaimTest {

    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private VisualBaselineService visualBaselineService;
    @Autowired
    private com.sixbean.testpike.core.executor.domain.component.ArtifactStorage artifactStorage;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        // Fixture-scoped teardown : the suite's projects carry its
        // fixed Chinese name prefix (kept in the SQL literal below); their baselines,
        // tasks, cases, steps, envs and project links fall through the shared
        // FK-tree sweep (tp_visual_baseline and the link tables are project-keyed
        // extras). Executors drop by the 'ex-visual-%' name family. Blanket
        // TRUNCATE/DELETE of these shared tables would wipe concurrent suites'
        // rows on the dev database.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate,
                List.of("tp_visual_baseline", "tp_project_env", "tp_user_project"),
                jdbcTemplate.queryForList(
                        "SELECT id FROM tp_project WHERE name LIKE '视觉下发测试%'", Long.class));
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-visual-%'");
    }

    private long createProject() {
        String code = "VC" + UUID.randomUUID().toString().replace("-", "").substring(0, 6).toUpperCase();
        return projectService.create(1L, new ProjectCreateInput(
                "视觉下发测试" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long projectId) {
        return projectEnvService.create(projectId, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
    }

    private long createCase(long projectId) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, "视觉下发用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    
    private VisualBaselineInfo seedConfirmed(long projectId, String stepKey, double tolerance) {
        String sourceKey = "testpike/9001/exec-artifact/202609/ab12cd34-screenshot_"
                + stepKey.hashCode() + ".png";
        byte[] png = new byte[]{(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4};
        artifactStorage.putBytes(sourceKey, png, "image/png");
        VisualBaselineInfo candidate = visualBaselineService.recordCandidate(
                new VisualBaselineCandidateInput(projectId, stepKey, 9001L, sourceKey, tolerance));
        return visualBaselineService.confirm(candidate.id(), 1L);
    }

    private ClaimTaskView claimTask(long projectId, long caseId, long envId, String actionJson) {
        execTaskService.create(new ExecTaskCreateInput(projectId, caseId, "v2", actionJson, envId));
        long exId = executorService.register(new ExecutorRegisterInput(
                "ex-visual-" + UUID.randomUUID().toString().substring(0, 6),
                null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "待派发任务必须可领取");
        return view;
    }

    @Test
    void 已确认基线_claim下发三键_seq线性跨setup计数() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        String stepKey = "case:" + caseId + ":step:3:home";
        VisualBaselineInfo confirmed = seedConfirmed(pid, stepKey, 0.05);
        String actionJson = """
                {"version":"v2",
                 "setup":[{"action":"goto","target":{"url":"http://demo.local/login"},"name":"登录"}],
                 "steps":[{"action":"goto","target":{"url":"http://demo.local"}},
                          {"action":"visual.assert","name":"home","tolerance":0.02}]}""";
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson);

        Map<String, Object> baselines = view.visualBaselines();
        assertNotNull(baselines, "claim 视图尾追 visualBaselines 字段");
        assertEquals(1, baselines.size(), "仅命中已确认基线的 visual.assert 步下发");
        @SuppressWarnings("unchecked")
        Map<String, Object> entry = (Map<String, Object>) baselines.get(stepKey);
        assertNotNull(entry, "stepKey 定位命中: " + baselines.keySet());
        assertEquals(confirmed.baselineKey(), entry.get("baselineKey"));
        assertNotNull(entry.get("baselineUrl"), "基线查看预签名 URL 随下发（执行器禁直连 DB）");
        assertTrue(String.valueOf(entry.get("baselineUrl")).startsWith("http"),
                "baselineUrl 为可 GET 地址");
        assertEquals(0.05, ((Number) entry.get("tolerance")).doubleValue(), 1e-9,
                "基线级容差覆写随下发");
    }

    @Test
    void 无已确认基线_空下发_执行器采集首图语义() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        String sourceKey = "testpike/9001/exec-artifact/202609/ab12cd34-screenshot_pend.png";
        artifactStorage.putBytes(sourceKey, new byte[]{1, 2, 3}, "image/png");
        visualBaselineService.recordCandidate(new VisualBaselineCandidateInput(
                pid, "case:" + caseId + ":step:1:shot", 1L, sourceKey, null));
        String actionJson = """
                {"version":"v2","steps":[{"action":"visual.assert"}]}""";
        ClaimTaskView view = claimTask(pid, caseId, envId, actionJson);
        assertNotNull(view.visualBaselines());
        assertTrue(view.visualBaselines().isEmpty(), "pending 非生效基线=不下发（采集首图）");
    }

    @Test
    void 显式baselineKey错配_不下发_一致则下发() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        String stepKey = "case:" + caseId + ":step:1:p";
        VisualBaselineInfo confirmed = seedConfirmed(pid, stepKey, 0.02);

        String mismatch = """
                {"version":"v2","steps":[{"action":"visual.assert","name":"p",
                 "baselineKey":"testpike/1/visual-baseline/202609/hacked.png"}]}""";
        ClaimTaskView v1 = claimTask(pid, caseId, envId, mismatch);
        assertTrue(v1.visualBaselines().isEmpty(), "显式键与生效基线不一致=不下发: "
                + v1.visualBaselines().keySet());

        String matched = """
                {"version":"v2","steps":[{"action":"visual.assert","name":"p",
                 "baselineKey":"%s"}]}""".formatted(confirmed.baselineKey());
        ClaimTaskView v2 = claimTask(pid, caseId, envId, matched);
        assertEquals(1, v2.visualBaselines().size(), "显式键与生效基线一致=下发");
        assertNotNull(v2.visualBaselines().get(stepKey));
    }

    @Test
    void name缺省shot_与无视觉步零开销() {
        long pid = createProject();
        long envId = createEnv(pid);
        long caseId = createCase(pid);
        String stepKey = "case:" + caseId + ":step:1:shot";
        seedConfirmed(pid, stepKey, 0.03);
        ClaimTaskView v1 = claimTask(pid, caseId, envId,
                "{\"version\":\"v2\",\"steps\":[{\"action\":\"visual.assert\"}]}");
        assertNotNull(v1.visualBaselines().get(stepKey), "无 name=缺省 shot 定位");

        ClaimTaskView v2 = claimTask(pid, caseId, envId,
                "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://a\"}}]}");
        assertNotNull(v2.visualBaselines(), "非视觉任务也回空 map 不 null（执行器免判空）");
        assertTrue(v2.visualBaselines().isEmpty());
    }
}
