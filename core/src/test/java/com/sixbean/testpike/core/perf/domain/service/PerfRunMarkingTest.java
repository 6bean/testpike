package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.perf.api.dto.PerfScenarioInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;

/**
 * B-7 golden-run marking on tp_perf_run (first segment of the baseline-collaboration task).
 * <p>Gap pinned by this class: before the fix tp_perf_run carries no mark column,
 * so a finished run can never be promoted to golden, demoted to candidate or voided,
 * and the run view exposes nothing a trend page or a golden-comparison band could
 * consume. The marking semantics this test locks:
 * <ul>
 * <li>three states GOLDEN / CANDIDATE / VOID plus unmarked (NULL) — existing rows
 * keep the untouched pre-feature meaning;</li>
 * <li>at most one golden per scenario: promoting a golden atomically demotes the
 * previous golden of the same scenario to CANDIDATE (service-side atomic UPDATE
 * family backed by a partial unique index as the last-resort DB guard);</li>
 * <li>only runs in a final state (2/3/4) may be marked — an active run rejects with
 * human wording;</li>
 * <li>unknown mark values reject with the accepted set named.</li>
 * </ul>
 * <p>Real chain, no mocks: dev DB fixtures + service calls (fixture posture follows
 * PerfRunEnvExclusionTest).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfRunMarkingTest {

    @Autowired
    private PerfScenarioService perfScenarioService;

    @Autowired
    private PerfRunService perfRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long devEnvId;
    private long operatorId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        operatorId = jdbcTemplate.queryForObject("SELECT min(id) FROM tp_user", Long.class);
        long[] seeded = SharedDbFixtureSweep.seedPerfFixtureProject(jdbcTemplate,
                "金跑打标测试项目_", "PFMARK", "开发-打标", 1,
                "http://perf-target.local/api", 100, "[\"SHADOW_TABLE\"]");
        projectId = seeded[0];
        devEnvId = seeded[1];
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
    }

    private void cleanupRows() {
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFMARK%')");
        jdbcTemplate.update("DELETE FROM tp_perf_run WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFMARK%')");
        jdbcTemplate.update("DELETE FROM tp_perf_scenario WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFMARK%')");
        jdbcTemplate.update("DELETE FROM tp_perf_whitelist WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFMARK%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PFMARK%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PFMARK%'");
    }

    private long newScenario(String name) {
        return perfScenarioService.create(projectId, new PerfScenarioInput(
                name, devEnvId, "CONSTANT_RPS",
                Map.of("targetRps", 20, "durationSeconds", 60),
                null, null,
                List.of(Map.of("action", "http.request", "method", "GET",
                        "url", "http://perf-target.local/api/orders")),
                null, null));
    }

    /** Launch a run and park it in the normal-finish final state so it is markable. */
    private long finishedRun(String scenarioName) {
        long scenarioId = newScenario(scenarioName);
        long runId = perfRunService.launch(projectId, scenarioId, operatorId);
        perfRunService.finish(projectId, runId, 2, null);
        return runId;
    }

    private String markOf(long runId) {
        return jdbcTemplate.queryForObject(
                "SELECT mark FROM tp_perf_run WHERE id = ?", String.class, runId);
    }

    /** Red anchor 1: a finished run can be promoted to golden and the run view echoes it. */
    @Test
    void mark_金跑晋升_视图回显且未打标行为空() {
        long runId = finishedRun("晋升场景");
        // Unmarked semantics before promotion: legacy rows never carry a mark.
        assertNull(markOf(runId), "未打标的运行行 mark 必须为 NULL（存量语义零迁移冲击）");

        perfRunService.mark(projectId, runId, "GOLDEN", operatorId);

        assertEquals("GOLDEN", markOf(runId));
        assertEquals("GOLDEN", perfRunService.view(projectId, runId).get("mark"),
                "运行视图必须带出 mark 供趋势页与金跑对比带消费");
    }

    /** Red anchor 2: at most one golden per scenario — the old golden demotes to candidate. */
    @Test
    void mark_同场景再晋升_旧金自动降候选() {
        long scenarioId = newScenario("唯一金跑场景");
        long firstRun = perfRunService.launch(projectId, scenarioId, operatorId);
        perfRunService.finish(projectId, firstRun, 2, null);
        long secondRun = perfRunService.launch(projectId, scenarioId, operatorId);
        perfRunService.finish(projectId, secondRun, 2, null);

        perfRunService.mark(projectId, firstRun, "GOLDEN", operatorId);
        perfRunService.mark(projectId, secondRun, "GOLDEN", operatorId);

        assertEquals("CANDIDATE", markOf(firstRun),
                "同场景晋升新金跑时旧金跑必须自动降为候选");
        assertEquals("GOLDEN", markOf(secondRun));
    }

    /** Red anchor 3: only final-state runs are markable; an active run rejects in human wording. */
    @Test
    void mark_运行中打标_人话拒绝() {
        long scenarioId = newScenario("在跑场景");
        long activeRun = perfRunService.launch(projectId, scenarioId, operatorId);

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.mark(projectId, activeRun, "GOLDEN", operatorId));
        assertTrue(e.getMessage().contains("运行中"),
                "拒单必须点名运行中不可打标: " + e.getMessage());
        assertNull(markOf(activeRun));
    }

    /** Red anchor 4: an unknown mark value rejects with the accepted set named. */
    @Test
    void mark_未知标记值_拒绝并点名可选值() {
        long runId = finishedRun("脏标记场景");

        ServiceException e = assertThrows(ServiceException.class,
                () -> perfRunService.mark(projectId, runId, "PLATINUM", operatorId));
        assertTrue(e.getMessage().contains("GOLDEN") && e.getMessage().contains("VOID"),
                "拒单必须列出可接受的标记值: " + e.getMessage());
    }

    /** Red anchor 5: VOID is a legal terminal mark (voided runs stay out of replay and
     *  golden-comparison consumption downstream). */
    @Test
    void mark_作废态_合法落库() {
        long runId = finishedRun("作废场景");

        perfRunService.mark(projectId, runId, "VOID", operatorId);

        assertEquals("VOID", markOf(runId));
    }

    /** Red anchor 6: the partial unique index is the last-resort DB guard — bypassing the
     *  service to force a second golden of the same scenario must fail closed. */
    @Test
    void mark_绕过服务直插二金跑_库级唯一约束兜底() {
        long scenarioId = newScenario("库级兜底场景");
        long firstRun = perfRunService.launch(projectId, scenarioId, operatorId);
        perfRunService.finish(projectId, firstRun, 2, null);
        long secondRun = perfRunService.launch(projectId, scenarioId, operatorId);
        perfRunService.finish(projectId, secondRun, 2, null);

        perfRunService.mark(projectId, firstRun, "GOLDEN", operatorId);

        assertThrows(DuplicateKeyException.class,
                () -> jdbcTemplate.update("UPDATE tp_perf_run SET mark = 'GOLDEN' WHERE id = ?",
                        secondRun),
                "同场景第二枚金跑绕过服务直写必须被部分唯一索引拒绝");
        assertEquals("GOLDEN", markOf(firstRun), "兜底拒插不得伤及已有金跑");
    }

    /** Red anchor 7 (GB-2 relay): a decoupled run — A-13 history whose scenario
     *  link is NULL — stays markable. The ledger page lists exactly this shape,
     *  and its detail door offers the same marking face; the old code
     *  dereferenced the NULL link before the mark branch and 500'd. */
    @Test
    void mark_decoupledRunWithoutScenarioLink_stillMarkable() {
        long runId = finishedRun("将脱钩的场景");
        // A-13 shape straight from the DB: recipe gone, link nulled.
        jdbcTemplate.update("UPDATE tp_perf_run SET scenario_id = NULL WHERE id = ?", runId);

        perfRunService.mark(projectId, runId, "GOLDEN", operatorId);

        assertEquals("GOLDEN", markOf(runId),
                "脱钩历史（无场景链接）必须仍可打标——台账页把它列进账里");
    }
}
