package com.sixbean.testpike.core.repair.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairItem;
import com.sixbean.testpike.core.repair.api.dto.ScriptRepairRecordInput;
import com.sixbean.testpike.core.repair.api.dto.TrialRepairTaskInput;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.api.dto.ScriptVersionInfo;
import com.sixbean.testpike.core.script.domain.component.ScriptVersionSupport;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Script-repair ledger suite (B4 base; docs 17 TC-B4-01 round self-derivation
 * and rollback trail + docs 18 IT-B4-01 repair-version chain with the AI-repair
 * snapshot reason). Runs against the real shared dev database, no mocks —
 * ScriptVersionServiceTest / TrainingSampleServiceTest precedent shape.
 * <p>Covers: 24h round window (rows outside it claim no round), outcome
 * value-range gate, missing case 404, project+case dual-filtered pagination,
 * and saveRepaired landing an AI-repair snapshot without touching the
 * original chain's reason semantics.
 *
 * <p>Shared-database discipline (R37): no blanket TRUNCATE. Project codes live
 * in the fixed RPs* family with a per-run token, a crashed round's leftovers
 * are family-swept before the round opens, and teardown deletes only this
 * suite's tracked projects with their descendants (tp_script_repair carries
 * project_id but no FK into tp_project, so it rides the extra-project-tables
 * channel). Every count assertion is project- or case-scoped, so no id
 * watermark is needed.
 */
@SpringBootTest(classes = CoreApplication.class)
class ScriptRepairServiceTest {

    @Autowired
    private ScriptRepairService scriptRepairService;

    @Autowired
    private ScriptService scriptService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** tp_script_repair carries project_id but no FK into tp_project. */
    private static final List<String> REPAIR_EXTRA_TABLES = List.of("tp_script_repair");

    /** Fixture project codes created by this suite (swept id-first). */
    private final List<Long> projectIds = new ArrayList<>();

    /** Per-run code token: unique-code rule can never collide across runs. */
    private String token;

    @BeforeEach
    void openFixture() {
        token = Long.toHexString(ThreadLocalRandom.current().nextLong(0x10000000L, 0xFFFFFFF0L));
        // Crash-residue guard: a killed round leaves RPs*-coded projects whose
        // codes the global uniqueness rule (40904) would reject on the next
        // create. Equality sweep of the family (never present in the shipped
        // baseline) removes them child-first before the round opens.
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, REPAIR_EXTRA_TABLES, familyProjectIds());
    }

    @AfterEach
    void sweepFixtureRows() {
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, REPAIR_EXTRA_TABLES, projectIds);
        projectIds.clear();
    }

    private List<Long> familyProjectIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'RPs%'", Long.class);
    }

    /** This run's code family; suffix keeps multi-project tests distinct. */
    private String fam(String suffix) {
        return "RPs" + token + suffix;
    }

    private long createProject(String code) {
        long id = projectService.create(1L,
                new ProjectCreateInput("修复项目" + code, code, "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private CaseInfo createCase(long projectId) {
        return caseService.create(1L, new CaseCreateInput(projectId, "登录功能测试", "打开登录页",
                "登录成功", 1, 2, "web", 1, null, 1, null,
                List.of(new CaseStepInput(1, "输入账号密码", 0))));
    }

    private static String actionJson(String title) {
        return "{\"version\":\"v2\",\"caseId\":\"TC-RP\",\"title\":\"" + title + "\",\"endType\":\"web\","
                + "\"setup\":[{\"action\":\"goto\",\"target\":{\"url\":\"/login\"}}],"
                + "\"steps\":[{\"action\":\"input\",\"target\":{\"css\":\"#u\"},\"value\":\"a\"}],"
                + "\"env\":{\"ENV\":\"dev\"}}";
    }

    private ScriptRepairRecordInput recordInput(long projectId, long caseId, int outcome) {
        return new ScriptRepairRecordInput(projectId, caseId, 101L, 202L, "定位器 #u 修正为 #username", outcome, null);
    }

    @Test
    void 记录轮次_24h窗内自推导_窗外行不占号() {
        long pid = createProject(fam("1"));
        CaseInfo info = createCase(pid);

        ScriptRepairItem r1 = scriptRepairService.record(recordInput(pid, info.id(), 2));
        ScriptRepairItem r2 = scriptRepairService.record(recordInput(pid, info.id(), 2));
        assertEquals(1, r1.round(), "首条=窗内第 1 轮");
        assertEquals(2, r2.round(), "第二条=第 2 轮");
        assertEquals(2L, scriptRepairService.countRoundsWithin24h(info.id()));

        jdbcTemplate.update("UPDATE tp_script_repair SET created_at = now() - interval '25 hours' WHERE id = ?",
                r1.id());
        assertEquals(1L, scriptRepairService.countRoundsWithin24h(info.id()), "窗外行不占轮次");
        ScriptRepairItem r3 = scriptRepairService.record(recordInput(pid, info.id(), 1));
        assertEquals(2, r3.round(), "新一轮从窗内行数+1 推导（历史窗外行不顶号）");
    }

    @Test
    void 记录outcome值域闸_非法40001人话() {
        long pid = createProject(fam("2"));
        CaseInfo info = createCase(pid);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptRepairService.record(recordInput(pid, info.id(), 4)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("1=通过 2=未过 3=闸外跳过"), "人话点名合法值域：" + ex.getMessage());
    }

    @Test
    void 记录用例不存在_404不静默落孤儿行() {
        long pid = createProject(fam("3"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptRepairService.record(recordInput(pid, 999999L, 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void 列表视图_项目用例双筛选_ID降序分页() {
        long pidA = createProject(fam("A"));
        long pidB = createProject(fam("B"));
        CaseInfo caseA = createCase(pidA);
        CaseInfo caseB = createCase(pidB);
        scriptRepairService.record(recordInput(pidA, caseA.id(), 1));
        scriptRepairService.record(recordInput(pidA, caseA.id(), 2));
        scriptRepairService.record(recordInput(pidB, caseB.id(), 3));

        PageResult<ScriptRepairItem> byProject = scriptRepairService.list(pidA, null, 1, 20);
        assertEquals(2L, byProject.total(), "项目筛选只含本项目行");
        assertEquals(2, byProject.list().get(0).round(), "id DESC 最新在前");

        PageResult<ScriptRepairItem> byCase = scriptRepairService.list(null, caseB.id(), 1, 20);
        assertEquals(1L, byCase.total());
        assertEquals(3, byCase.list().get(0).outcome());
        assertEquals("闸外跳过", byCase.list().get(0).outcomeLabel(), "视图带人话结局标签（展示源）");
        assertTrue(byCase.list().get(0).createdAt().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
                "时间格式化口径同台账先例");
    }

    @Test
    void saveRepaired_落AI修复快照_版本线递增原脚本更新() {
        long pid = createProject(fam("4"));
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A"), 1));

        scriptService.saveRepaired(info.id(), "v2", actionJson("登录A修复"));

        List<ScriptVersionInfo> versions = scriptService.listVersions(info.id());
        assertEquals(2, versions.size());
        assertEquals(ScriptVersionSupport.REASON_AI_REPAIR, versions.get(0).reason(),
                "修复落版 reason=AI修复（B5 快照复用禁二套写点）");
        assertEquals(ScriptVersionSupport.REASON_SAVE, versions.get(1).reason(), "既有 reason 语义零触碰");
        assertEquals(2, versions.get(0).scriptVersion());
        String title = jdbcTemplate.queryForObject(
                "SELECT action_json #>> '{title}' FROM tp_script WHERE case_id = ?", String.class, info.id());
        assertEquals("登录A修复", title, "修复提案过闸后落 tp_script 现行");
    }


    private long seedEnv(long pid, String baseUrl, String browsers) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot, browsers)
                VALUES (?, '修复验证环境', 2, ?, 'v1.0.0', ?) RETURNING id
                """, Long.class, pid, baseUrl, browsers);
    }

    private long seedSourceTask(long pid, long caseId, String baseUrl, String browser) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_task (project_id, case_id, status, schema_version,
                                          action_json, env_base_url, env_version_snapshot, browser)
                VALUES (?, ?, 4, 'v2', ?, ?, 'v1.0.0', COALESCE(?, 'chromium')) RETURNING id
                """, Long.class, pid, caseId, actionJson("登录A"), baseUrl, browser);
    }

    private void cleanRepairFootprint(long... projectIds) {
        for (long pid : projectIds) {
            jdbcTemplate.update("DELETE FROM tp_exec_task WHERE project_id = ?", pid);
            jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id = ?", pid);
        }
    }

    @Test
    void 试运行链路_源任务浏览器透传_batchId置空防污染批次计数() {
        long pid = createProject(fam("T1"));
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A"), 1));
        seedEnv(pid, "http://tp-local.test", "chromium,firefox");
        long source = seedSourceTask(pid, info.id(), "http://tp-local.test", "firefox");
        try {
            long trialId = scriptRepairService.trialTask(
                    new TrialRepairTaskInput(source, "v2", actionJson("登录A修复")));
            assertTrue(trialId > 0);
            Map<String, Object> row = jdbcTemplate.queryForMap(
                    "SELECT batch_id, browser, env_base_url, status,"
                            + " action_json::jsonb #>> '{title}' AS title"
                            + " FROM tp_exec_task WHERE id = ?", trialId);
            assertNull(row.get("batch_id"), "试运行不挂批次=防污染 I7 报告/批次计数");
            assertEquals("firefox", row.get("browser"), "源任务浏览器透传（H3 尾矩阵内合法）");
            assertEquals("http://tp-local.test", row.get("env_base_url"), "环境按基址反查命中");
            assertEquals(1, row.get("status"), "新任务=待执行");
            assertEquals("登录A修复", row.get("title"), "下发的是修复提案脚本而非原脚本");
        } finally {
            cleanRepairFootprint(pid);
        }
    }

    @Test
    void 试运行链路_源任务不存在_404人话() {
        createProject(fam("T2"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scriptRepairService.trialTask(
                        new TrialRepairTaskInput(999_999_999L, "v2", actionJson("修复"))));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("来源任务不存在"), "人话点名：" + ex.getMessage());
    }

    @Test
    void 试运行链路_环境已删或基址变更_404人话failClosed() {
        long pid = createProject(fam("T3"));
        CaseInfo info = createCase(pid);
        seedEnv(pid, "http://tp-local.test", "chromium");
        long source = seedSourceTask(pid, info.id(), "http://tp-deleted.test", null);
        try {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> scriptRepairService.trialTask(
                            new TrialRepairTaskInput(source, "v2", actionJson("修复"))));
            assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
            assertTrue(ex.getMessage().contains("无法发起修复验证"), "人话点名：" + ex.getMessage());
        } finally {
            cleanRepairFootprint(pid);
        }
    }

    @Test
    void saveRepaired_非法动作JSON被静态校验拒绝_原脚本零触碰() {
        long pid = createProject(fam("5"));
        CaseInfo info = createCase(pid);
        scriptService.save(new ScriptSaveInput(info.id(), "v2", actionJson("登录A"), 1));

        assertThrows(ServiceException.class,
                () -> scriptService.saveRepaired(info.id(), "v2", "{not-json"));
        String title = jdbcTemplate.queryForObject(
                "SELECT action_json #>> '{title}' FROM tp_script WHERE case_id = ?", String.class, info.id());
        assertEquals("登录A", title, "回滚语义=非法提案原脚本零触碰");
        assertEquals(1, scriptService.listVersions(info.id()).size(), "不落半截快照");
    }
}
