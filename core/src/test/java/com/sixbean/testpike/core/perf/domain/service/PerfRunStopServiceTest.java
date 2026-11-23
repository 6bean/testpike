package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import com.sixbean.testpike.core.perf.domain.service.PerfRunService;
import com.sixbean.testpike.core.perf.domain.service.PerfScenarioService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.PerfLaunchFixture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunStopServiceTest {

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private PerfRunStopService perfRunStopService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long envId;

    @BeforeEach
    void seedStopFixture() {
        cleanupStopRows();
        long[] ids = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "停压通道测试项目_", "PERFCHAIN",
                "性能-停压测", "http://perf-target.local/api");
        projectId = ids[0];
        envId = ids[1];
    }

    @AfterEach
    void cleanupStopFixture() {
        cleanupStopRows();
    }

    private void cleanupStopRows() {
        // Fixture-scoped teardown :
        // the PERFCHAIN project family with its FK tree (reaches tp_exec_task and the
        // tp_perf_* tables; covers the PERFCHAINX foreign rows too). The old blanket
        // TRUNCATE wiped rows owned by concurrent suites and the persistent runner.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'PERFCHAIN%'", Long.class));
    }

    
    private long newRunningRun() {
        // This suite tests the stop channel explicitly: launch through the real
        // chain, then detach the auto-dispatch row so dispatchTask owns a
        // controlled envelope. Single-source fixture (R40): PerfLaunchFixture.
        return PerfLaunchFixture.launchDetachedRun(
                jdbcTemplate, perfScenarioService, perfRunService, projectId, envId,
                "停压契约场景_" + System.nanoTime(),
                Map.of("targetRps", 40, "durationSeconds", 60));
    }

    /** Second status=1 run on the same scenario ( GA-2 lesson: launching a
 * second run on a locked environment is a legitimate rejection, while the
 * stop-addressing face under test only needs the run rows to exist — the
 * fixture therefore duplicates a run row directly instead of racing the
 * environment lock. Zero launch, zero dispatch, snapshot copied verbatim). */
    private long secondRunningRun(long templateRunId) {
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT scenario_id, pressure_snapshot FROM tp_perf_run WHERE id = ?",
                templateRunId);
        return jdbcTemplate.queryForObject("INSERT INTO tp_perf_run (scenario_id, project_id, "
                        + "status, pressure_snapshot) VALUES (?, ?, 1, ?) RETURNING id",
                Long.class, row.get("scenario_id"), projectId, row.get("pressure_snapshot"));
    }

    private long dispatchTask(long runId) {
        return execTaskService.createPerfRunTask(projectId, "{\"kind\":\"perf-run\",\"runId\":" + runId
                + ",\"engine\":\"K6\",\"artifact\":\"/tmp/perf-" + runId + ".js\"}");
    }

    
    @Test
    void stopRun_运行中转人工停_任务置取消态_kill走batchCancel单源() {
        long runId = newRunningRun();
        long taskId = dispatchTask(runId);

        int cancelled = perfRunStopService.stopRun(projectId, runId, 7L);

        assertEquals(1, cancelled, "名下待派发任务必须被终止 1 条");
        Map<String, Object> run = perfRunService.view(projectId, runId);
        assertEquals(4, ((Number) run.get("status")).intValue(), "运行必须置 4 人工停");
        String reason = String.valueOf(run.get("stop_reason"));
        assertTrue(reason.contains("人工停"), "停因必须人话: " + reason);
        assertTrue(reason.contains("7"), "停因含操作人可追溯: " + reason);

        Integer taskStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(7, taskStatus, "任务置已取消(7)（复用 T231 单源终态，禁新状态值）");
        String detail = jdbcTemplate.queryForObject(
                "SELECT error_detail FROM tp_exec_task WHERE id = ?", String.class, taskId);
        assertTrue(detail.contains("取消"), "任务侧终止语义原样来自 cancel 单源: " + detail);
    }

    
    @Test
    void stopRun_无在途任务_仅运行行转人工停() {
        long runId = newRunningRun();

        int cancelled = perfRunStopService.stopRun(projectId, runId, 1L);

        assertEquals(0, cancelled);
        assertEquals(4, ((Number) perfRunService.view(projectId, runId).get("status")).intValue());
    }

    
    @Test
    void stopRun_已结束运行_拒止人话点名() {
        long runId = newRunningRun();
        perfRunService.finish(projectId, runId, 2, "正常结束夹具");

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunStopService.stopRun(projectId, runId, 1L));
        assertTrue(e.getMessage().contains("结束") || e.getMessage().contains("不存在"),
                "人话点名: " + e.getMessage());
    }

    
    @Test
    void stopRun_跨项目_不泄漏存在性() {
        long runId = newRunningRun();
        long uniq = System.nanoTime() % 1_000_000_000L;
        long foreign = jdbcTemplate.queryForObject(
                "INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1) RETURNING id",
                Long.class, "停压他项目_" + uniq, "PERFCHAINX" + uniq);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunStopService.stopRun(foreign, runId, 1L));
        assertEquals(40401, e.getCode().intValue());
    }

    
    @Test
    void stopRun_只杀本运行任务_他运行与异类任务不误伤() {
        long runA = newRunningRun();
        long runB = secondRunningRun(runA);
        long taskA = dispatchTask(runA);
        long taskB = dispatchTask(runB);
        long browserTask = execTaskService.createExploreAct(projectId,
                "{\"kind\":\"explore-act\",\"action\":\"page-snapshot\",\"url\":\"http://x\"}");

        perfRunStopService.stopRun(projectId, runA, 1L);

        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskA));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskB),
                "他运行任务不得被连坐");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, browserTask),
                "非压测任务不得被误伤");
        assertEquals(1, ((Number) perfRunService.view(projectId, runB).get("status")).intValue(),
                "他运行状态不动");
    }

    
    @Test
    void stopAll_只停本项目运行中行_他项目不受波及() {
        long run1 = newRunningRun();
        long run2 = secondRunningRun(run1);
        long[] foreignIds = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "停压他项目_", "PERFCHAINX",
                "他-停压测", "http://other.local/api");
        long foreignProject = foreignIds[0];
        long foreignEnv = foreignIds[1];
        long foreignScenario = perfScenarioService.create(foreignProject, new PerfScenarioInput(
                "他项目停压场景", foreignEnv, "CONSTANT_RPS",
                Map.of("targetRps", 10, "durationSeconds", 30), null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://other.local/api")), null));
        long foreignRun = perfRunService.launch(foreignProject, foreignScenario, null);
        long task2 = dispatchTask(run2);

        int stopped = perfRunStopService.stopAll(projectId, 9L);

        assertEquals(2, stopped, "本项目两条运行中全部停");
        assertEquals(4, ((Number) perfRunService.view(projectId, run1).get("status")).intValue());
        assertEquals(4, ((Number) perfRunService.view(projectId, run2).get("status")).intValue());
        assertEquals(7, jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, task2));
        assertEquals(1, ((Number) perfRunService.view(foreignProject, foreignRun).get("status"))
                .intValue(), "他项目运行不得被波及");
    }

    
    @Test
    void stopAll_无运行中_返回零不报错() {
        assertEquals(0, perfRunStopService.stopAll(projectId, 1L));
    }
}
