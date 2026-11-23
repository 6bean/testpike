package com.sixbean.testpike.core.sched.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.project.domain.service.MemberService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
import com.sixbean.testpike.core.sched.domain.component.ScheduleCronTimes;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.suites.api.dto.SuiteCreateInput;
import com.sixbean.testpike.core.suites.domain.service.RegressionSuiteService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Schedule-fire base suite (docs 17 I2): cron previous-fire computation with
 * an injected clock (never the real one), fail-closed validation of malformed
 * expressions, the regression-plan chain (fire creates a batch once, trace
 * row success, rescan idempotent, last_run_at consumption, disabled never
 * fires), the batch-template chain (reuse creates batches, missing target
 * env leaves a human-readable failed trace, enabled=0 never fires), catch-up
 * semantics (in-window re-fire annotated, out-of-window leaves zero traces)
 * and swallowed trigger failures that never kill the scan. Runs against the
 * real shared dev database, no mocks.
 *
 * <p>Shared-database discipline (R37): no blanket TRUNCATE. Project codes and
 * tester usernames live in the fixed TSF* family plus a per-run token; a
 * crashed round's leftovers are family-swept before the round opens so the
 * global unique-code rule can never trip, and teardown deletes only this
 * run's tracked testers and projects with their descendants.
 * tp_batch_template and tp_schedule_fire carry project_id without an FK into
 * tp_project, so they ride the extra-project-tables channel; tp_executor is
 * deliberately never swept — it is the shared fleet registry owned by the
 * running services. Every count and trace assertion is project- or
 * ref-key-scoped: the old blanket wipe made global counts look empty, on a
 * shared database they would drift with factory and concurrent rows.
 */
@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ScheduleFireServiceTest {

    
    private static final String EVERY_MINUTE = "0 * * * * *";
    
    private static final String DAILY_02 = "0 0 2 * * *";

    @Autowired
    private ScheduleFireService scheduleFireService;
    @Autowired
    private RegressionSuiteService suiteService;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private MemberService memberService;
    @Autowired
    private RoleService roleService;
    @Autowired
    private ScriptService scriptService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** tp_batch_template / tp_schedule_fire carry project_id with no FK into
     * tp_project (live schema scan 2026-11-16): they ride the extra channel
     * on top of the suites-family list. tp_executor is never swept (shared
     * fleet registry, R37 postmortem note in SharedDbFixtureSweep). */
    private static final List<String> FIRE_EXTRA_TABLES;

    static {
        List<String> tables = new ArrayList<>(SharedDbFixtureSweep.SUITE_FAMILY_EXTRA_TABLES);
        tables.addAll(List.of("tp_batch_template", "tp_schedule_fire"));
        FIRE_EXTRA_TABLES = List.copyOf(tables);
    }

    /** Fixture projects created by this run (swept child-first at teardown). */
    private final List<Long> projectIds = new ArrayList<>();

    /** Fixture tester accounts created by this run (username-unique rows the
     * project sweep cannot reach). */
    private final List<Long> testers = new ArrayList<>();

    /** Per-run code token: the global unique-code rule can never collide. */
    private String token;

    @BeforeEach
    void openFixture() {
        token = Long.toHexString(ThreadLocalRandom.current().nextLong(0x10000000L, 0xFFFFFFF0L));
        // Crash-residue guard: a killed round leaves TSF*-coded projects and
        // schedt_* testers whose codes the global uniqueness rules would
        // reject on the next create. Both families (absent from the shipped
        // baseline) are swept — plans first while parents join, then users —
        // before the round opens.
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, FIRE_EXTRA_TABLES, familyProjectIds());
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "schedt_%");
    }

    @AfterEach
    void sweepFixtureRows() {
        SharedDbFixtureSweep.usersDown(jdbcTemplate, testers);
        testers.clear();
        SharedDbFixtureSweep.plansTreeDown(jdbcTemplate, FIRE_EXTRA_TABLES, projectIds);
        projectIds.clear();
    }

    private List<Long> familyProjectIds() {
        return jdbcTemplate.queryForList(
                "SELECT id FROM tp_project WHERE code LIKE 'TSF%'", Long.class);
    }

    /** This run's code family; the suffix keeps multi-project tests distinct. */
    private String fam(String suffix) {
        return "TSF" + token + suffix;
    }

    private static Instant at(String localDateTime) {
        return Timestamp.valueOf(LocalDateTime.parse(localDateTime)).toInstant();
    }

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput(
                "项目" + fam(code), fam(code), "zh-CN", "P1", null));
        projectIds.add(id);
        return id;
    }

    private long createTester(long pid, String username) {
        return SharedDbFixtureSweep.createTrackedTester(
                jdbcTemplate, roleService, memberService, username, pid, testers);
    }

    private long createEnv(long pid, String name) {
        return createEnv(pid, name, 2);
    }

    private long createEnv(long pid, String name, int envType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_project_env (project_id, name, env_type, base_url, version_snapshot)
                VALUES (?, ?, ?, 'http://tp-local.test', 'v1.0.0') RETURNING id
                """, Long.class, pid, name, envType);
    }

    
    private long createRunnableSuite(long pid, String envName, String cron) {
        long env = createEnv(pid, envName);
        CaseInfo c = caseService.create(1L, new CaseCreateInput(pid, "回归用例", "前置", "预期",
                1, 2, "web", 3, 1, 1, null, List.of()));
        scriptService.save(new ScriptSaveInput(c.id(), "v2",
                "{\"caseId\": " + c.id() + ", \"setup\": [], \"steps\": []}", 1));
        long tester = createTester(pid, "schedt_" + pid + "_" + envName.length());
        return suiteService.create(1L, new SuiteCreateInput(pid, "夜间回归", List.of(c.id()),
                List.of(env), List.of(tester), cron, 1)).id();
    }

    /** Batches of this run's projects (global counts drift on a shared db). */
    private long batchCount(long... pids) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE project_id IN (" + placeholders(pids) + ")",
                Long.class, boxed(pids));
        return n == null ? 0 : n;
    }

    /** Cron-reused batches of this run's projects (excludes template source batches). */
    private long templateBatchCount(long... pids) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE template_id IS NOT NULL"
                        + " AND project_id IN (" + placeholders(pids) + ")",
                Long.class, boxed(pids));
        return n == null ? 0 : n;
    }

    private static String placeholders(long... pids) {
        StringBuilder ph = new StringBuilder();
        for (int i = 0; i < pids.length; i++) {
            ph.append(i == 0 ? "?" : ",?");
        }
        return ph.toString();
    }

    private static Object[] boxed(long... pids) {
        return java.util.Arrays.stream(pids).boxed().toArray();
    }


    @Test
    void test_cron_previous_fire_point() {
        Optional<Instant> after = ScheduleCronTimes.previousFire(DAILY_02, at("2026-09-25T03:30:00"));
        assertEquals(at("2026-09-25T02:00:00"), after.orElseThrow());
        Optional<Instant> before = ScheduleCronTimes.previousFire(DAILY_02, at("2026-09-25T01:00:00"));
        assertEquals(at("2026-09-24T02:00:00"), before.orElseThrow());
        assertTrue(ScheduleCronTimes.previousFire(DAILY_02, at("2026-09-25T02:00:00"))
                .orElseThrow().isBefore(at("2026-09-25T02:00:00")));
    }

    @Test
    void test_cron_validate_fail_closed() {
        ScheduleCronTimes.validate(DAILY_02);
        ScheduleCronTimes.validate("0 30 2 * * MON");
        ScheduleCronTimes.validate(null);
        ScheduleCronTimes.validate("  ");
        ServiceException invalid = assertThrows(ServiceException.class,
                () -> ScheduleCronTimes.validate("bad cron 123"));
        assertTrue(invalid.getMessage().contains("cron"), invalid.getMessage());
        assertTrue(invalid.getMessage().contains("6 段"), invalid.getMessage());
        ServiceException unix5 = assertThrows(ServiceException.class,
                () -> ScheduleCronTimes.validate("0 2 * * 1"));
        assertTrue(unix5.getMessage().contains("6 段"), unix5.getMessage());
    }


    @Test
    void test_suite_fire_creates_batch_once_and_idempotent() {
        long pid = createProject("S1");
        long suiteId = createRunnableSuite(pid, "测试环境A", EVERY_MINUTE);
        Instant now = at("2026-09-25T03:00:30");

        int fired = scheduleFireService.scanAndFire(now);
        assertEquals(1, fired, "到点触发一次");
        assertEquals(1, batchCount(pid), "回归计划一键生成的批次在位");

        List<String> rows = jdbcTemplate.queryForList("""
                SELECT status || '|' || coalesce(message, '') FROM tp_schedule_fire
                WHERE kind = 'suite' AND ref_id = ?
                """, String.class, suiteId);
        assertEquals(1, rows.size());
        assertTrue(rows.get(0).startsWith("success|"), rows.get(0));

        int again = scheduleFireService.scanAndFire(now.plusSeconds(20));
        assertEquals(0, again, "上期触发点已被 last_run_at 消费");
        assertEquals(1, batchCount(pid), "批次未被重复生成");
    }

    @Test
    void test_suite_disabled_out_of_window_no_fire() {
        long pid = createProject("S2");
        long disabled = jdbcTemplate.queryForObject("""
                INSERT INTO tp_regression_suite (project_id, name, case_ids_json, env_ids_json,
                                                 default_assignee_ids_json, cron_expr, status)
                VALUES (?, '停用计划', '[]', '[]', '[]', ?, 2) RETURNING id
                """, Long.class, pid, EVERY_MINUTE);
        long stale = createRunnableSuite(pid, "环境S", DAILY_02);

        int fired = scheduleFireService.scanAndFire(at("2026-09-25T12:00:00"));
        assertEquals(0, fired, "停用不触发 + 超补偿窗口不追（防重启风暴）");
        assertEquals(0, batchCount(pid));
        List<String> kinds = jdbcTemplate.queryForList(
                "SELECT kind || ref_id FROM tp_schedule_fire WHERE project_id = ?",
                String.class, pid);
        assertTrue(kinds.isEmpty(), "超窗不追=零留痕（TC-I2-01）");
        assertNotNull(disabled);
        assertNotNull(stale);
    }

    @Test
    void test_suite_catchup_marks_message() {
        long pid = createProject("S3");
        long suiteId = createRunnableSuite(pid, "环境C", DAILY_02);
        int fired = scheduleFireService.scanAndFire(at("2026-09-25T03:30:00"));
        assertEquals(1, fired, "窗口内补跑");
        String message = jdbcTemplate.queryForObject("""
                SELECT message FROM tp_schedule_fire WHERE kind = 'suite' AND ref_id = ?
                """, String.class, suiteId);
        assertTrue(message.contains("补偿触发"), message);
        assertEquals(1, batchCount(pid));
    }

    @Test
    void test_suite_trigger_failure_recorded_not_loop_crash() {
        long pid = createProject("S4");
        long broken = jdbcTemplate.queryForObject("""
                INSERT INTO tp_regression_suite (project_id, name, case_ids_json, env_ids_json,
                                                 default_assignee_ids_json, cron_expr, status)
                VALUES (?, '空用例计划', '[]', '[]', '[]', ?, 1) RETURNING id
                """, Long.class, pid, EVERY_MINUTE);
        long pidOk = createProject("S4B");
        long ok = createRunnableSuite(pidOk, "环境OK", EVERY_MINUTE);

        Instant now = at("2026-09-25T03:00:30");
        Instant fire = at("2026-09-25T03:00:00");
        int fired = scheduleFireService.scanAndFire(now);
        assertEquals(1, fired, "异常计划不计数，正常计划照常触发");
        jdbcTemplate.update("""
                INSERT INTO tp_schedule_fire (kind, ref_id, project_id, fire_time, status)
                VALUES ('suite', ?, ?, now(), 'fired') ON CONFLICT DO NOTHING
                """, broken, pid);
        String failed = jdbcTemplate.queryForObject("""
                SELECT status || '|' || message FROM tp_schedule_fire
                WHERE kind = 'suite' AND ref_id = ? AND fire_time = ?
                """, String.class, broken, Timestamp.from(fire));
        assertTrue(failed.startsWith("failed|"), failed);
        assertTrue(failed.contains("回归集内无用例"), failed);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_schedule_fire WHERE kind = 'suite' AND ref_id = ?"
                        + " AND status = 'success' AND fire_time = ?",
                Long.class, ok, Timestamp.from(fire)).intValue(), "第二计划成功留痕（锚本用例触发点）");
    }


    private long createTemplate(long pid, String cron, int enabled, Long envId) {
        long env = envId != null ? envId : createEnv(pid, "模板环境");
        CaseInfo c = caseService.create(1L, new CaseCreateInput(pid, "模板用例", "前置", "预期",
                1, 2, "web", 3, 1, 1, null, List.of()));
        long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, schedule_type, created_by)
                VALUES (?, ?, 'B-tpl-src', '来源批', 2, 1, 0, 1) RETURNING id
                """, Long.class, pid, env);
        jdbcTemplate.update("INSERT INTO tp_batch_case (batch_id, case_id, case_version, sort_order, result) " +
                "VALUES (?, ?, 1, 1, 0)", batchId, c.id());
        long tpl = jdbcTemplate.queryForObject("""
                INSERT INTO tp_batch_template (project_id, name, case_ids, created_by,
                                               cron_expr, enabled, schedule_env_id)
                VALUES (?, '夜间复用模板', ?::jsonb, 1, ?, ?, ?) RETURNING id
                """, Long.class, pid, "[" + c.id() + "]", cron, enabled, envId);
        return tpl;
    }

    @Test
    void test_template_fire_creates_batch_from_template() {
        long pid = createProject("S5");
        long env = createEnv(pid, "模板环境A");
        long tpl = createTemplate(pid, EVERY_MINUTE, 1, env);

        int fired = scheduleFireService.scanAndFire(at("2026-09-25T03:00:30"));
        assertEquals(1, fired);
        Long batches = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_batch WHERE template_id = ? AND schedule_type = 1",
                Long.class, tpl);
        assertEquals(1, batches, "复用建批在位且 schedule_type=cron");

        fired = scheduleFireService.scanAndFire(at("2026-09-25T03:01:30"));
        assertEquals(1, fired, "新触发点照常触发");
        fired = scheduleFireService.scanAndFire(at("2026-09-25T03:01:40"));
        assertEquals(0, fired, "fire 唯一键幂等");
    }

    @Test
    void test_template_disabled_or_no_env() {
        long pid = createProject("S6");
        createTemplate(pid, EVERY_MINUTE, 0, createEnv(pid, "模板环境X", 3));
        long noEnv = createTemplate(pid, EVERY_MINUTE, 1, createEnv(pid, "模板环境Y", 2));
        jdbcTemplate.update("UPDATE tp_batch_template SET enabled = 1, schedule_env_id = NULL WHERE id = ?", noEnv);

        int fired = scheduleFireService.scanAndFire(at("2026-09-25T03:00:30"));
        assertEquals(0, fired, "缺环境不计数");
        assertEquals(0, templateBatchCount(pid), "零复用批次生成");
        String failed = jdbcTemplate.queryForObject("""
                SELECT status || '|' || message FROM tp_schedule_fire
                WHERE kind = 'template' AND ref_id = ?
                """, String.class, noEnv);
        assertTrue(failed.startsWith("failed|"), failed);
        assertTrue(failed.contains("环境"), failed);
    }



    @Test
    void test_list_fires_filters_and_pagination() {
        long pidA = createProject("F1");
        long pidB = createProject("F2");
        Instant t1 = at("2026-09-25T02:00:00");
        Instant t2 = at("2026-09-25T03:00:00");
        scheduleFireService.tryFire(ScheduleFireService.KIND_SUITE, 7001, pidA, t1);
        scheduleFireService.recordResult(ScheduleFireService.KIND_SUITE, 7001, t1, "success", "已生成计划");
        scheduleFireService.tryFire(ScheduleFireService.KIND_TEMPLATE, 7002, pidA, t2);
        scheduleFireService.recordResult(ScheduleFireService.KIND_TEMPLATE, 7002, t2, "failed", "缺环境");
        scheduleFireService.tryFire(ScheduleFireService.KIND_SUITE, 7003, pidB, t1);
        scheduleFireService.recordResult(ScheduleFireService.KIND_SUITE, 7003, t1, "success", "他项目留痕");

        var all = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pidA, null, null, 1, 20));
        assertEquals(2, all.total(), "H1 项目隔离：只回本项目留痕");
        assertEquals("template", all.list().get(0).kind(), "fire_time 倒序=最近触发在前");
        assertEquals("failed", all.list().get(0).status());
        assertEquals("缺环境", all.list().get(0).message());
        assertNotNull(all.list().get(0).fireTime(), "理论触发点人话可读格式在位");

        var suiteOnly = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pidA, "suite", null, 1, 20));
        assertEquals(1, suiteOnly.total(), "kind 筛选=suite 链路");
        assertEquals("已生成计划", suiteOnly.list().get(0).message());

        var failedB = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pidB, null, "failed", 1, 20));
        assertEquals(0, failedB.total(), "status 筛选=仅失败（pidB 无失败）");
        var failedA = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pidA, null, "failed", 1, 20));
        assertEquals(1, failedA.total());
        assertEquals(7002L, failedA.list().get(0).refId());

        var page2 = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pidA, null, null, 2, 1));
        assertEquals(2, page2.total(), "total=总数不随页码变");
        assertEquals(1, page2.list().size(), "第 2 页取到 1 条");
        assertEquals("suite", page2.list().get(0).kind(), "倒序第 2 页=较早的 suite 留痕");
    }

    @Test
    void test_list_fires_message_not_truncated() {
        long pid = createProject("S8");
        Instant t = at("2026-09-25T04:00:00");
        String longMsg = "失败原因：" + "执".repeat(300);
        scheduleFireService.tryFire(ScheduleFireService.KIND_SUITE, 7010, pid, t);
        scheduleFireService.recordResult(ScheduleFireService.KIND_SUITE, 7010, t, "failed", longMsg);
        var rows = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 1, 20)).list();
        assertEquals(1, rows.size());
        assertEquals(longMsg, rows.get(0).message(), "人话消息完整返回不截断（用户铁律：信息完整）");
    }

    /**
     * The fire ledger answers header sorts from one core-owned whitelist
     * (five physical columns — no jsonb facets on this table), so "oldest
     * first" or "all templates grouped" is a SQL truth across the whole
     * ledger, not a shuffle of the loaded page. Clearing the sort keeps the
     * historical fire_time DESC order untouched.
     */
    @Test
    void test_list_fires_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("FS1");
        Instant t1 = at("2026-09-25T01:00:00");
        Instant t2 = at("2026-09-25T02:00:00");
        Instant t3 = at("2026-09-25T03:00:00");
        // Filed so every whitelisted column answers in a different order than
        // fire_time — a client-side leftover would fail these assertions loudly.
        scheduleFireService.tryFire(ScheduleFireService.KIND_TEMPLATE, 8003, pid, t1);
        scheduleFireService.recordResult(ScheduleFireService.KIND_TEMPLATE, 8003, t1, "failed", "较早失败");
        scheduleFireService.tryFire(ScheduleFireService.KIND_SUITE, 8001, pid, t2);
        scheduleFireService.recordResult(ScheduleFireService.KIND_SUITE, 8001, t2, "success", "中间成功");
        scheduleFireService.tryFire(ScheduleFireService.KIND_SUITE, 8002, pid, t3);
        scheduleFireService.recordResult(ScheduleFireService.KIND_SUITE, 8002, t3, "failed", "最近失败");

        var kindDesc = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 1, 20, "kind", "desc"));
        assertEquals("template", kindDesc.list().get(0).kind(), "kind desc leads with template");
        assertEquals(8002L, kindDesc.list().get(1).refId(), "suite group keeps id DESC tie-break");
        assertEquals(8001L, kindDesc.list().get(2).refId());

        var refAsc = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 1, 20, "ref_id", "asc"));
        assertEquals(8001L, refAsc.list().get(0).refId(), "ref_id asc = 8001/8002/8003");
        assertEquals(8003L, refAsc.list().get(2).refId());

        var statusAsc = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 1, 20, "status", "asc"));
        assertEquals("failed", statusAsc.list().get(0).status(), "failed sorts before success");
        assertEquals("success", statusAsc.list().get(2).status());

        // Sorting still pages: fire_time asc = t1(8003), t2(8001), t3(8002),
        // so window 2 of size 2 carries the tail of the sorted set.
        var secondWindow = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 2, 2, "fire_time", "asc"));
        assertEquals(3L, secondWindow.total(), "total stays truthful under sorting");
        assertEquals(8002L, secondWindow.list().get(0).refId(), "sorted paging lands the tail row");

        // Off-list/injection-shaped keys are refused fail-closed with the
        // allowed list named in plain words.
        var injection = assertThrows(ServiceException.class, () -> scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(
                        pid, null, null, 1, 20, "fire_time; DROP TABLE tp_schedule_fire", "asc")));
        assertTrue(injection.getMessage().contains("fire_time"), injection.getMessage());

        // No sort requested = the historical fire_time DESC order, untouched.
        var unsorted = scheduleFireService.listFires(
                new com.sixbean.testpike.core.sched.api.dto.ScheduleFireQuery(pid, null, null, 1, 20));
        assertEquals(8002L, unsorted.list().get(0).refId(), "default stays fire_time DESC");
        assertEquals(8003L, unsorted.list().get(2).refId());
    }

    @Test
    void test_try_fire_dedup_and_record_result() {
        long pid = createProject("S7");
        Instant fire = at("2026-09-25T02:00:00");
        assertTrue(scheduleFireService.tryFire(ScheduleFireService.KIND_TEMPLATE, 9001, pid, fire));
        assertFalse(scheduleFireService.tryFire(ScheduleFireService.KIND_TEMPLATE, 9001, pid, fire),
                "同触发点重复占位=已被触发（多实例安全）");
        scheduleFireService.recordResult(ScheduleFireService.KIND_TEMPLATE, 9001, fire, "success", "已完成");
        List<String> rows = jdbcTemplate.queryForList(
                "SELECT status || message FROM tp_schedule_fire WHERE ref_id = 9001", String.class);
        assertEquals(List.of("success已完成"), rows);
        Long matched = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_schedule_fire WHERE ref_id = 9001 AND project_id = ?"
                        + " AND fire_time = ?",
                Long.class, pid, Timestamp.from(fire));
        assertEquals(1, matched, "留痕字段与入参一致");
    }
}
