package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.project.api.dto.EnvParamsInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(
        properties = "testpike.prod-blacklist=black.example.com")
class BatchParamSnapshotTest {

    @Autowired
    private BatchService batchService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private ProjectEnvService projectEnvService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private ExecTaskService execTaskService;
    @Autowired
    private ExecutorService executorService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // appauto dispatch suite): projects live in the name family below (codes
    // SNAP*), the FK child-first sweep covers tp_script/tp_test_case/
    // tp_project_env/tp_user_project under tp_project, tp_exec_task/tp_exec_batch
    // among the project-keyed defaults, and tp_batch_param_snapshot/
    // tp_batch_runtime_change as FK children of the batch rows. tp_batch_case has
    // no FK and no project column, so this family's rows purge first through the
    // batch rows still present; tp_batch_template is project-keyed without an FK
    // and rides extraProjectTables. This suite's executors purge by the
    // 'ex-snap' name family. The old CASCADE wiped shipped tp_user/
    // tp_notification_pref rows and every other suite's executors — the sweep
    // leaves them alone.
    private static final String FIXTURE_PROJECT_NAME_PREFIX = "项目SNAP";

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
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-snap%'");
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid, String baseUrl) {
        return projectEnvService.create(pid, new ProjectEnvInput("测试环境", 2,
                baseUrl, null, "web", "chromium", "v1.0.0"));
    }

    private long createApprovedCaseWithScript(long pid) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "快照链用例", "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        scriptService.save(new ScriptSaveInput(info.id(), "v2",
                "{\"caseId\":" + info.id() + ",\"steps\":[]}", 2));
        return info.id();
    }

    private BatchInfo startBatch(long pid, long envId, long caseId) {
        BatchInfo batch = batchService.create(1L,
                new BatchCreateInput(pid, "快照批", envId, List.of(caseId), null));
        batchService.start(1L, batch.id());
        return batchService.get(batch.id());
    }

    private long registerExecutor(String name) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
    }

    @Test
    void start_落参数快照_ENC密文入快照表() {
        long pid = createProject("SNAP1");
        long envId = createEnv(pid, "https://snap1.example.com");
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v1")));
        long caseId = createApprovedCaseWithScript(pid);

        BatchInfo batch = startBatch(pid, envId, caseId);

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT params_json, gen FROM tp_batch_param_snapshot WHERE batch_id = ?", batch.id());
        assertEquals(1, rows.size(), "启动必须恰好钉一版参数快照");
        String paramsJson = (String) rows.get(0).get("params_json");
        assertTrue(paramsJson.startsWith("ENC:"), "快照值必 ENC 密文落盘（红线4，禁明文入参快照）");
        assertEquals(1, ((Number) rows.get(0).get("gen")).intValue(), "首次启动轮次 gen=1");
    }

    @Test
    void claim_读快照不反查_启动后改参数信封仍旧值() {
        long pid = createProject("SNAP2");
        long envId = createEnv(pid, "https://snap2.example.com");
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v1")));
        long caseId = createApprovedCaseWithScript(pid);
        startBatch(pid, envId, caseId);

        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v2")));

        long exId = registerExecutor("ex-snap2");
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "执行器必须领取到任务");
        assertEquals("qa_v1", view.envVars().get("ACCOUNT"),
                "claim 信封 envVars 必须=启动钉版值（B2 丙：批内不可变，结果可复现）");
    }

    @Test
    void 存量批无快照_claim回落实时反查零漂移() {
        long pid = createProject("SNAP3");
        long envId = createEnv(pid, "https://snap3.example.com");
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "legacy_v1")));
        long caseId = createApprovedCaseWithScript(pid);
        BatchInfo batch = startBatch(pid, envId, caseId);
        jdbcTemplate.update("DELETE FROM tp_batch_param_snapshot WHERE batch_id = ?", batch.id());

        long exId = registerExecutor("ex-snap3");
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "无快照批禁炸领取（回落实时反查）");
        assertEquals("legacy_v1", view.envVars().get("ACCOUNT"),
                "回落路径=旧语义：实时反查源表当前值");
    }

    @Test
    void 运行期源表变更_批次计数_终态批不计() {
        long pid = createProject("SNAP4");
        long envId = createEnv(pid, "https://snap4.example.com");
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v1")));
        long caseId = createApprovedCaseWithScript(pid);
        BatchInfo batch = startBatch(pid, envId, caseId);

        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v2")));
        Long cntRunning = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_runtime_change WHERE batch_id = ? AND kind = 'env'",
                Long.class, batch.id());
        assertEquals(1L, cntRunning, "在跑批运行期 env 参数变更必须计数 1（详情人话提示数据源）");

        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 6 WHERE id = ?", batch.id());
        projectEnvService.saveParams(pid, envId, new EnvParamsInput(Map.of("ACCOUNT", "qa_v3")));
        Long cntFinal = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_runtime_change WHERE batch_id = ?", Long.class, batch.id());
        assertEquals(1L, cntFinal, "终态批变更不计数（钉版语义=批生命周期内口径）");
    }

    @Test
    void v121_K8键seed在位_TTL全键可配() {
        List<String> keys = jdbcTemplate.queryForList(
                "SELECT config_key FROM tp_sys_config WHERE config_key IN ("
                        + "'batch.snapshot-ttl-hours','lock.env-exec-lease-seconds',"
                        + "'notify.nonce-ttl-seconds','ratelimit.callback-window-seconds',"
                        + "'ratelimit.mcp-window-seconds') ORDER BY config_key",
                String.class);
        assertEquals(List.of("batch.snapshot-ttl-hours", "lock.env-exec-lease-seconds",
                        "notify.nonce-ttl-seconds", "ratelimit.callback-window-seconds",
                        "ratelimit.mcp-window-seconds"), keys,
                "v2.56 口径 a：五件 Redis 用途 TTL 键必须全部 seed 在位（禁硬编码）");
    }
}
