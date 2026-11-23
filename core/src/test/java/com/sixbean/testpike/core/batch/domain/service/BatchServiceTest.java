package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.batch.api.dto.BatchCaseInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchFromTemplateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchQuery;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.api.dto.BatchTemplateInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchUpdateInput;
import com.sixbean.testpike.core.batch.domain.service.BatchService;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.api.dto.CaseUpdateInput;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.api.dto.ProjectUpdateInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.script.api.dto.ScriptSaveInput;
import com.sixbean.testpike.core.script.domain.service.ScriptService;
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
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class BatchServiceTest {

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
    private com.sixbean.testpike.core.executor.domain.service.ExecTaskService execTaskService;
    @Autowired
    private com.sixbean.testpike.core.config.domain.service.ConfigService configService;
    @Autowired
    private com.sixbean.testpike.core.mock.domain.service.MockSpaceService mockSpaceService;
    @Autowired
    private com.sixbean.testpike.core.executor.domain.service.ExecutorService executorService;
    @Autowired
    private com.sixbean.testpike.core.batch.domain.component.BatchFinalizeSupport batchFinalizeSupport;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ObjectMapper SNAPSHOT_MAPPER = new ObjectMapper();

    // Fixture-scoped teardown (truncate-ratchet discipline, sub-batch H):
    // projects live in the name families below (codes I1*/I6*/I9*/SCH*/V6*/
    // BF*) plus the tracked-ids set — the test-owner flow renames its
    // project to a fixed display name (the literal rides the prefix list
    // below), which a stale prefix could not resolve after a crash, so
    // createProject tracks every id it mints. The FK child-first
    // sweep covers tp_script/tp_test_case/tp_user_project and the project-
    // keyed defaults tp_exec_task/tp_exec_batch; tp_batch_case carries no
    // FK and no project column, so its rows purge first through the batch
    // rows still present; tp_batch_template is project-keyed without an
    // FK and rides extraProjectTables. BATCH_FAILED/BATCH_FINISHED in-site
    // rows (tp_notification carries no project FK) purge through the
    // dedup-key batch segment before the sweep — the assertion
    // isolation the blanket truncate used to provide, now run-scoped.
    // Fixture users purge by username family (usersDown walks their
    // notification prefs and received rows too); executors purge by the
    // ex-i6/ex-bf name families. The old CASCADE wiped shipped tp_user/
    // tp_notification_pref rows and every other suite's data.
    private static final List<String> FIXTURE_PROJECT_NAME_PREFIXES =
            List.of("项目I1", "项目I6", "项目I9", "项目SCH", "项目V6", "项目BF", "批次项目");

    private final java.util.List<Long> fixtureProjectIds = new java.util.ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanTables() {
        java.util.List<Long> ids = new java.util.ArrayList<>(fixtureProjectIds);
        ids.addAll(com.sixbean.testpike.core.support.SharedDbFixtureSweep
                .projectIdsByNamePrefixes(jdbcTemplate, FIXTURE_PROJECT_NAME_PREFIXES));
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchCaseRowsOfProjects(
                jdbcTemplate, ids);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.batchNotificationsOfProjects(
                jdbcTemplate, ids);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectList(
                jdbcTemplate, List.of("tp_batch_template"), ids);
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.usersByPatternDown(
                jdbcTemplate, "bf%-testowner");
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.usersByPatternDown(
                jdbcTemplate, "member-i6b4");
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-i6%'"
                + " OR name LIKE 'ex-bf%'");
        fixtureProjectIds.clear();
    }

    private long createProject(String code) {
        long id = projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
        fixtureProjectIds.add(id);
        return id;
    }

    
    private long createEnv(long pid, String name, String endTypes) {
        return createEnv(pid, name, endTypes, 2);
    }

    private long createEnv(long pid, String name, String endTypes, int envType) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, envType,
                "https://" + name + ".example.com", null, endTypes, "chromium", "v1.0.0"));
    }

    private long createCase(long pid, String title, String endType) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, endType, 3, 2, 1, null, List.of()));
        return info.id();
    }

    private BatchCreateInput createInput(long pid, String name, Long envId, List<Long> caseIds) {
        return new BatchCreateInput(pid, name, envId, caseIds, null);
    }

    
    private BatchCreateInput createInput(long pid, String name, Long envId, List<Long> caseIds,
                                         Integer notifyFeishu) {
        return new BatchCreateInput(pid, name, envId, caseIds, null, notifyFeishu);
    }

    
    private void approve(long... caseIds) {
        for (long id : caseIds) {
            jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", id);
        }
    }

    
    private void withScript(long caseId) {
        scriptService.save(new ScriptSaveInput(caseId, "v2",
                "{\"caseId\":" + caseId + ",\"steps\":[]}", 2));
    }

    
    private static java.util.Map<Long, Integer> snapshotOf(JdbcTemplate jdbc, long batchId)
            throws Exception {
        String json = jdbc.queryForObject(
                "SELECT case_version_snapshot::text FROM tp_exec_batch WHERE id = ?",
                String.class, batchId);
        java.util.Map<Long, Integer> map = new java.util.HashMap<>();
        if (json != null) {
            for (JsonNode n : SNAPSHOT_MAPPER.readTree(json)) {
                map.put(n.get("caseId").asLong(), n.get("version").asInt());
            }
        }
        return map;
    }


    @Test
    void test_i1_create_draft_batch_with_case_snapshot() {
        long pid = createProject("I1A");
        long env = createEnv(pid, "测试环境", "web,mobile");
        long c1 = createCase(pid, "登录用例", "web");
        long c2 = createCase(pid, "下单用例", "web");
        long c3 = createCase(pid, "APP 支付", "mobile");

        BatchInfo batch = batchService.create(1L, createInput(pid, "周末回归", env, List.of(c1, c2, c3)));
        assertEquals(1, batch.status(), "创建后=草稿");
        assertEquals("草稿", batch.statusLabel());
        assertEquals(3, batch.caseCount());
        assertNotNull(batch.batchNo(), "批次号自动生成");
        assertEquals("测试环境", batch.envName());

        List<BatchCaseInfo> cases = batchService.listCases(batch.id());
        assertEquals(3, cases.size());
        assertTrue(cases.stream().allMatch(c -> c.result() == 4 && "未执行".equals(c.resultLabel())),
                "新成员=未执行");
        assertTrue(cases.stream().anyMatch(c -> "登录用例".equals(c.title())));

        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_batch_case WHERE batch_id = ?", Long.class, batch.id());
        assertEquals(3L, rows);
    }


    @Test
    void test_v6_create_notifyFeishu_落库与回显() {
        long pid = createProject("V6A");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "开关用例", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "飞书批", env, List.of(c1), 1));
        assertEquals(1, batch.notifyFeishu(), "建批开态回显");
        Integer db = jdbcTemplate.queryForObject(
                "SELECT notify_feishu FROM tp_exec_batch WHERE id = ?", Integer.class, batch.id());
        assertEquals(1, db, "真实库落档（reload 语义）");
    }

    @Test
    void test_v6_create_默认关_temp与旧签名零变化() {
        long pid = createProject("V6B");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "默认用例", "web");
        BatchInfo norm = batchService.create(1L, createInput(pid, "常规批", env, List.of(c1)));
        assertEquals(0, norm.notifyFeishu(), "旧 5 参签名=默认 0=存量行为零变化");
        Integer db = jdbcTemplate.queryForObject(
                "SELECT notify_feishu FROM tp_exec_batch WHERE id = ?", Integer.class, norm.id());
        assertEquals(0, db, "默认关真实落库（temp/模板复用路同走 DB 列默认 0，不显式写列）");
    }

    @Test
    void test_v6_create_notifyFeishu_非法值_400人话() {
        long pid = createProject("V6C");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "非法用例", "web");
        for (int bad : new int[]{2, -1}) {
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> batchService.create(1L, createInput(pid, "非法批", env, List.of(c1), bad)),
                    "非法 notifyFeishu 应拒: " + bad);
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
            assertTrue(ex.getMessage().contains("飞书"), "人话文案: " + ex.getMessage());
        }
    }

    @Test
    void test_i1_draft_edit_add_remove_idempotent() {
        long pid = createProject("I1B");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        long c2 = createCase(pid, "用例乙", "web");
        long c3 = createCase(pid, "用例丙", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "草稿批", env, List.of(c1, c2)));

        BatchInfo updated = batchService.update(1L, batch.id(),
                new BatchUpdateInput("改名校验", null, List.of(c3, c1), List.of(c2)));
        assertEquals("改名校验", updated.name());
        assertEquals(2, updated.caseCount(), "c1 重复加幂等，c2 已删 → 2 条");
        List<Long> caseIds = batchService.listCases(batch.id()).stream()
                .map(BatchCaseInfo::caseId).toList();
        assertEquals(List.of(c1, c3), caseIds.stream().sorted().toList());

        BatchInfo afterGhost = batchService.update(1L, batch.id(),
                new BatchUpdateInput(null, null, null, List.of(999999L)));
        assertEquals(2, afterGhost.caseCount());
    }


    @Test
    void test_i1_update_rejected_when_not_draft() {
        long pid = createProject("I1C");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "运行批", env, List.of(c1)));
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 4 WHERE id = ?", batch.id());

        ServiceException ex = assertThrows(ServiceException.class, () ->
                batchService.update(1L, batch.id(),
                        new BatchUpdateInput("偷改", null, List.of(c1), null)));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertTrue(ex.getMessage().contains("草稿"), "人话含状态约束：" + ex.getMessage());
    }


    @Test
    void test_i1_end_type_env_mismatch_blocked() {
        long pid = createProject("I1D");
        long posEnv = createEnv(pid, "POS环境", "pos");
        long webCase = createCase(pid, "网页用例", "web");
        long posCase = createCase(pid, "POS用例", "pos");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                batchService.create(1L, createInput(pid, "错配批", posEnv, List.of(webCase, posCase))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("网页用例"), "人话点名不匹配用例：" + ex.getMessage());

        long multiCase = createCase(pid, "多端用例", "multi");
        BatchInfo ok = batchService.create(1L, createInput(pid, "合规批", posEnv, List.of(multiCase)));
        assertEquals(1, ok.caseCount());

        ServiceException ex2 = assertThrows(ServiceException.class, () ->
                batchService.update(1L, ok.id(), new BatchUpdateInput(null, null, List.of(webCase), null)));
        assertTrue(ex2.getMessage().contains("网页用例"));
    }

    @Test
    void test_i1_cross_project_and_validation() {
        long pid = createProject("I1E");
        long otherPid = createProject("I1X");
        long env = createEnv(pid, "测试环境", "web");
        long foreignCase = createCase(otherPid, "他人用例", "web");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                batchService.create(1L, createInput(pid, "越界批", env, List.of(foreignCase))));
        assertTrue(ex.getMessage().contains("不属于该项目"));

        assertThrows(ServiceException.class, () ->
                batchService.create(1L, createInput(pid, "空批", env, List.of())));

        long otherEnv = createEnv(otherPid, "他人环境", "web");
        long ownCase = createCase(pid, "本项目用例", "web");
        assertThrows(ServiceException.class, () ->
                batchService.create(1L, createInput(pid, "错环境", otherEnv, List.of(ownCase))));
    }


    @Test
    void test_i1_list_filters_and_excludes_temp() {
        long pid = createProject("I1F");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        BatchInfo b1 = batchService.create(1L, createInput(pid, "周末回归", env, List.of(c1)));
        batchService.create(1L, createInput(pid, "冒烟批", env, List.of(c1)));
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 4 WHERE id = ?", b1.id());
        jdbcTemplate.update("""
                INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type)
                VALUES (?, 'T-1', '快速执行临时批', 4, 2)
                """, pid);

        PageResult<BatchInfo> all = batchService.list(new BatchQuery(pid, null, null, 1, 20));
        assertEquals(2, all.total(), "默认视图排除临时批");
        PageResult<BatchInfo> running = batchService.list(new BatchQuery(pid, 4, null, 1, 20));
        assertEquals(1, running.total());
        PageResult<BatchInfo> byKeyword = batchService.list(new BatchQuery(pid, null, "冒烟", 1, 20));
        assertEquals(1, byKeyword.total());
        assertEquals("冒烟批", byKeyword.list().get(0).name());
    }

    // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

    @Test
    void test_i1_list_sorts_by_whitelisted_columns_both_directions() {
        long pid = createProject("I1SRT");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        // Insertion order deliberately opposite to name order, so id-DESC default
        // cannot accidentally satisfy the asc assertion.
        batchService.create(1L, createInput(pid, "Z批次", env, List.of(c1)));
        batchService.create(1L, createInput(pid, "A批次", env, List.of(c1)));
        batchService.create(1L, createInput(pid, "M批次", env, List.of(c1)));

        PageResult<BatchInfo> asc = batchService.list(new BatchQuery(pid, null, null, 1, 20, "name", "asc"));
        assertEquals(java.util.List.of("A批次", "M批次", "Z批次"),
                asc.list().stream().map(BatchInfo::name).toList(), "asc must reorder server-side");

        PageResult<BatchInfo> desc = batchService.list(new BatchQuery(pid, null, null, 1, 20, "name", "desc"));
        assertEquals(java.util.List.of("Z批次", "M批次", "A批次"),
                desc.list().stream().map(BatchInfo::name).toList(), "desc mirrors asc");

        // Default order (no sort requested) keeps the historical id-DESC behaviour.
        PageResult<BatchInfo> dflt = batchService.list(new BatchQuery(pid, null, null, 1, 20));
        assertEquals(java.util.List.of("M批次", "A批次", "Z批次"),
                dflt.list().stream().map(BatchInfo::name).toList(), "default stays id DESC");
    }

    @Test
    void test_i1_list_rejects_non_whitelisted_sort_key() {
        long pid = createProject("I1SREJ");
        assertThrows(ServiceException.class,
                () -> batchService.list(new BatchQuery(pid, null, null, 1, 20, "id; DROP TABLE x", "asc")),
                "injection-shaped sort key must fail closed");
    }


    @Test
    void test_i1_template_save_and_reuse() {
        long pid = createProject("I1G");
        long envA = createEnv(pid, "环境A", "web", 2);
        long envB = createEnv(pid, "环境B", "web", 3);
        long c1 = createCase(pid, "用例甲", "web");
        long c2 = createCase(pid, "用例乙", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "源批", envA, List.of(c1, c2)));

        BatchTemplateInfo tpl = batchService.saveAsTemplate(1L, batch.id(), "周末回归模板");
        assertEquals(List.of(c1, c2), tpl.caseIds().stream().sorted().toList());
        assertEquals(1, batchService.listTemplates(pid).size());

        BatchInfo rebuilt = batchService.createFromTemplate(1L,
                new BatchFromTemplateInput(tpl.id(), "本周复用批", envB));
        assertEquals(2, rebuilt.caseCount());
        assertEquals(tpl.id(), rebuilt.templateId());
        assertEquals("环境B", rebuilt.envName());
        assertEquals("草稿", rebuilt.statusLabel());

        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", c2);
        BatchInfo pruned = batchService.createFromTemplate(1L,
                new BatchFromTemplateInput(tpl.id(), "剔除复用批", envB));
        assertEquals(1, pruned.caseCount());
        assertNotNull(pruned.message());
        assertTrue(pruned.message().contains("已删除"), "剔除提示人话：" + pruned.message());

        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", c1);
        assertThrows(ServiceException.class, () ->
                batchService.createFromTemplate(1L,
                        new BatchFromTemplateInput(tpl.id(), "空批", envB)));
    }

    @Test
    void test_i1_template_cross_project_rejected_and_delete_isolation() {
        long pid = createProject("I1H");
        long otherPid = createProject("I1Y");
        long env = createEnv(pid, "测试环境", "web");
        long foreignEnv = createEnv(otherPid, "他人环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "源批", env, List.of(c1)));
        BatchTemplateInfo tpl = batchService.saveAsTemplate(1L, batch.id(), "模板甲");

        ServiceException ex = assertThrows(ServiceException.class, () ->
                batchService.createFromTemplate(1L,
                        new BatchFromTemplateInput(tpl.id(), "跨项目批", foreignEnv)));
        assertTrue(ex.getMessage().contains("项目"), "人话含项目限制：" + ex.getMessage());

        BatchInfo reused = batchService.createFromTemplate(1L,
                new BatchFromTemplateInput(tpl.id(), "复用批", env));
        batchService.deleteTemplate(1L, tpl.id());
        assertEquals(0, batchService.listTemplates(pid).size());
        assertNotNull(batchService.get(reused.id()), "已建批次不受模板删除影响");
        assertEquals(reused.templateId(), batchService.get(reused.id()).templateId(),
                "来源引用保留追溯");
    }


    @Test
    void test_template_schedule_configure_and_validation() {
        long pid = createProject("SCH1");
        long env = createEnv(pid, "定時環境", "web");
        long c1 = createCase(pid, "可定时用例", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "来源批", env, List.of(c1)));
        BatchTemplateInfo tpl = batchService.saveAsTemplate(1L, batch.id(), "定时模板");
        assertEquals(0, tpl.enabled(), "存量模板默认定时停用（V98 零行为变化）");
        org.junit.jupiter.api.Assertions.assertNull(tpl.cronExpr());

        BatchTemplateInfo saved = batchService.updateTemplateSchedule(1L, tpl.id(),
                new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput("0 30 2 * * *", 1, env));
        assertEquals("0 30 2 * * *", saved.cronExpr());
        assertEquals(1, saved.enabled());
        assertEquals(env, saved.scheduleEnvId().longValue());

        ServiceException bad = assertThrows(ServiceException.class, () ->
                batchService.updateTemplateSchedule(1L, tpl.id(),
                        new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput("nope 123", 1, env)));
        assertTrue(bad.getMessage().contains("cron"), bad.getMessage());

        ServiceException noEnv = assertThrows(ServiceException.class, () ->
                batchService.updateTemplateSchedule(1L, tpl.id(),
                        new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput("0 30 2 * * *", 1, null)));
        assertTrue(noEnv.getMessage().contains("环境"), noEnv.getMessage());

        assertThrows(ServiceException.class, () ->
                batchService.updateTemplateSchedule(1L, tpl.id(),
                        new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput(null, 1, env)));

        long foreignEnv = createEnv(createProject("SCHY"), "他人環境", "web");
        ServiceException cross = assertThrows(ServiceException.class, () ->
                batchService.updateTemplateSchedule(1L, tpl.id(),
                        new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput("0 30 2 * * *", 1, foreignEnv)));
        assertTrue(cross.getMessage().contains("项目"), cross.getMessage());

        BatchTemplateInfo off = batchService.updateTemplateSchedule(1L, tpl.id(),
                new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput(null, 0, null));
        assertEquals(0, off.enabled());
        org.junit.jupiter.api.Assertions.assertNull(off.cronExpr());

        assertThrows(ServiceException.class, () ->
                batchService.updateTemplateSchedule(999L, tpl.id(),
                        new com.sixbean.testpike.core.batch.api.dto.TemplateScheduleInput("0 30 2 * * *", 1, env)));
    }


    @Test
    void test_i1_project_purge_cleans_batch_children() {
        long pid = createProject("I1Z");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        BatchInfo batch = batchService.create(1L, createInput(pid, "待清批", env, List.of(c1)));
        batchService.saveAsTemplate(1L, batch.id(), "待清模板");

        projectService.purge(1L, pid, true);

        assertEquals(0, count("SELECT count(*) FROM tp_batch_case WHERE batch_id = " + batch.id()),
                "tp_batch_case 须随项目 purge 级联清");
        assertEquals(0, count("SELECT count(*) FROM tp_batch_template WHERE project_id = " + pid),
                "tp_batch_template 须随项目 purge 级联清");
        assertEquals(0, count("SELECT count(*) FROM tp_exec_batch WHERE project_id = " + pid),
                "tp_exec_batch 级联清（既有）");
    }

    private long count(String sql) {
        Long v = jdbcTemplate.queryForObject(sql, Long.class);
        return v == null ? 0 : v;
    }

    private long taskCount(long batchId) {
        return count("SELECT count(*) FROM tp_exec_task WHERE batch_id = " + batchId);
    }


    @Test
    void test_i1_start_draft_batch_snapshots_and_queues_tasks() throws Exception {
        long pid = createProject("I1S1");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "登录用例", "web");
        long c2 = createCase(pid, "下单用例", "web");
        approve(c1, c2);
        withScript(c1);
        withScript(c2);
        BatchInfo batch = batchService.create(1L, createInput(pid, "启动批", env, List.of(c1, c2)));

        BatchStartResult r = batchService.start(1L, batch.id());
        assertTrue(r.status() == 2 || r.status() == 4,
                "启动后离开草稿（2待执行/4执行中）：" + r.status());
        assertEquals(r.status(), batchService.get(batch.id()).status(), "详情状态与启动响应一致");
        java.util.Map<Long, Integer> snap = snapshotOf(jdbcTemplate, batch.id());
        assertEquals(2, snap.size(), "case_version_snapshot 固化两成员");
        assertEquals(1, snap.get(c1));
        assertEquals(1, snap.get(c2));
        assertEquals(2, taskCount(batch.id()), "两任务入队 tp_exec_task");
        assertTrue(r.queuePos() != null && r.queuePos() >= 1, "排队位回填：" + r.queuePos());
        assertNotNull(r.estimatedStartAt(), "预计启动时间供人话展示");
    }

    @Test
    void test_i1_start_rejected_when_not_draft() {
        long pid = createProject("I1S2");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        approve(c1);
        withScript(c1);
        BatchInfo batch = batchService.create(1L, createInput(pid, "重复启动批", env, List.of(c1)));
        batchService.start(1L, batch.id());

        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.start(1L, batch.id()));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, ex.getCode());
        assertTrue(ex.getMessage().contains("草稿"), "人话含状态约束：" + ex.getMessage());
        assertEquals(1, taskCount(batch.id()), "重复启动不追加任务（幂等拒绝）");
    }

    @Test
    void test_i1_start_skips_soft_deleted_member_into_skip_json() throws Exception {
        long pid = createProject("I1S3");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        long c2 = createCase(pid, "用例乙", "web");
        approve(c1, c2);
        withScript(c1);
        withScript(c2);
        BatchInfo batch = batchService.create(1L, createInput(pid, "软删成员批", env, List.of(c1, c2)));
        jdbcTemplate.update("UPDATE tp_test_case SET deleted = 1 WHERE id = ?", c2);

        BatchStartResult r = batchService.start(1L, batch.id());
        assertEquals(1, r.skipCases().size(), "软删成员启动跳过入清单");
        assertEquals(c2, r.skipCases().get(0).caseId());
        assertTrue(r.skipCases().get(0).reason().contains("删除"),
                "跳过原因人话：" + r.skipCases().get(0).reason());
        assertEquals(1, taskCount(batch.id()), "仅存活用例入队");
        String skipJson = jdbcTemplate.queryForObject(
                "SELECT skip_cases_json FROM tp_exec_batch WHERE id = ?", String.class, batch.id());
        assertNotNull(skipJson, "跳过清单落 skip_cases_json（N6 同列）");
        assertTrue(skipJson.contains("用例乙"));
    }

    @Test
    void test_i1_start_no_script_cases_counted_in_message() {
        long pid = createProject("I1S4");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "有脚本用例", "web");
        long c2 = createCase(pid, "裸用例", "web");
        approve(c1, c2);
        withScript(c1);
        BatchInfo batch = batchService.create(1L, createInput(pid, "缺脚本批", env, List.of(c1, c2)));

        BatchStartResult r = batchService.start(1L, batch.id());
        assertEquals(1, taskCount(batch.id()), "无脚本用例不建任务");
        assertNotNull(r.message(), "人话汇总携带无脚本计数");
        assertTrue(r.message().contains("无脚本") && r.message().contains("1"),
                "message 含「无脚本…1」：" + r.message());
    }

    @Test
    void test_i1_start_blocked_and_allowed_by_mock_exit_gate() {
        // D2 exit-plan mock ban (ruling M-f/U-c): mock-bound member + gate on = refuse;
        // gate off (0) = launch; gate on without mock members = unchanged launch.
        long pid = createProject("I1MG1");
        long env = createEnv(pid, "测试环境", "web");
        long spaceId = publishedMockSpace(pid);
        CaseInfo mockCase = caseService.create(1L, new CaseCreateInput(pid, "挂Mock用例", "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of(), null, null, null, null, null, spaceId));
        approve(mockCase.id());
        withScript(mockCase.id());
        BatchInfo b1 = batchService.create(1L, createInput(pid, "mock批", env, List.of(mockCase.id())));

        // dev DB seeds mock.exit-plan.forbid=1 (V228) — refusal is the default posture
        ServiceException ex = assertThrows(ServiceException.class, () -> batchService.start(1L, b1.id()));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("mock.exit-plan.forbid"), ex.getMessage());
        assertTrue(ex.getMessage().contains("真实"), ex.getMessage());
        assertEquals(0, taskCount(b1.id()), "拒单零副作用（不建任务）");

        // key=0 (hot config change) = allow; existing behavior zero-drift when disabled
        com.sixbean.testpike.core.config.api.dto.ConfigInfo cur = configService.list("接口仿真").stream()
                .filter(c -> "mock.exit-plan.forbid".equals(c.configKey())).findFirst().orElseThrow();
        configService.update(1L, cur.id(), new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                "mock.exit-plan.forbid", "0", cur.groupName(), cur.valueType(), 0, cur.description()));
        try {
            BatchStartResult r = batchService.start(1L, b1.id());
            assertTrue(r.status() == 2 || r.status() == 4, "停用后照常启动：" + r.status());
            assertEquals(1, taskCount(b1.id()), "停用后任务入队");
        } finally {
            configService.update(1L, cur.id(), new com.sixbean.testpike.core.config.api.dto.ConfigInput(
                    "mock.exit-plan.forbid", "1", cur.groupName(), cur.valueType(), 0, cur.description()));
        }

        // gate back on: a batch without mock-bound members launches untouched
        long c2 = createCase(pid, "真实用例", "web");
        approve(c2);
        withScript(c2);
        BatchInfo b2 = batchService.create(1L, createInput(pid, "真实批", env, List.of(c2)));
        BatchStartResult r2 = batchService.start(1L, b2.id());
        assertTrue(r2.status() == 2 || r2.status() == 4, "无 mock 成员不受闸：" + r2.status());
    }

    /** Create and publish a mock space (published is the only bindable state). */
    private long publishedMockSpace(long pid) {
        String key = "t364bg" + java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        long id = mockSpaceService.create(pid, new com.sixbean.testpike.core.mock.api.dto.MockSpaceInput(
                "Mock " + key, key, null, null, "prefix", "none", null, null, 100, null));
        mockSpaceService.transition(id, 1, mockSpaceService.get(id).version());
        return id;
    }

    @Test
    void test_i1_start_snapshot_freezes_version_at_start_time() throws Exception {
        long pid = createProject("I1S5");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "版本用例", "web");
        approve(c1);
        withScript(c1);
        BatchInfo early = batchService.create(1L, createInput(pid, "早批", env, List.of(c1)));
        int vAtStart = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, c1);
        batchService.start(1L, early.id());

        int vAfterEnqueue = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, c1);
        caseService.update(1L, c1, new CaseUpdateInput("改版后的标题", null, null,
                null, null, null, null, null), vAfterEnqueue);
        int vEdited = jdbcTemplate.queryForObject(
                "SELECT version FROM tp_test_case WHERE id = ?", Integer.class, c1);
        assertTrue(vEdited > vAfterEnqueue, "编辑后版本前进（前提校验）");

        assertEquals(vAtStart, snapshotOf(jdbcTemplate, early.id()).get(c1),
                "早批快照=启动时版本不随编辑漂移");
        BatchInfo late = batchService.create(1L, createInput(pid, "晚批", env, List.of(c1)));
        batchService.start(1L, late.id());
        assertEquals(vEdited, snapshotOf(jdbcTemplate, late.id()).get(c1),
                "晚批快照=其启动时版本");
    }


    
    private BatchInfo startedAndClaimed(long pid, long env, int caseCount, String execName) {
        List<Long> caseIds = new java.util.ArrayList<>();
        for (int i = 1; i <= caseCount; i++) {
            long c = createCase(pid, "控制台用例" + i, "web");
            approve(c);
            withScript(c);
            caseIds.add(c);
        }
        BatchInfo batch = batchService.create(1L, createInput(pid, "控制台批", env, caseIds));
        batchService.start(1L, batch.id());
        long exId = executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                execName, null, null, "web", "chromium", 2, "1.0.0", 0));
        assertNotNull(execTaskService.claim(exId), "首任务可领取");
        return batch;
    }

    @Test
    void test_i6_claim_advances_batch_to_running() {
        long pid = createProject("I6A1");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6a1");
        BatchInfo after = batchService.get(batch.id());
        assertEquals(4, after.status(), "领取后批次=执行中(4)");
        assertEquals("执行中", after.statusLabel());
    }

    @Test
    void test_i6_pause_stops_dispatch_and_resume_continues() {
        long pid = createProject("I6A2");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 3, "ex-i6a2");

        BatchInfo paused = batchService.pause(1L, batch.id());
        assertEquals(5, paused.status(), "暂停态=5");
        long exId = executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "ex-i6a2b", null, null, "web", "chromium", 2, "1.0.0", 0));
        assertNull(execTaskService.claim(exId), "已暂停批次不再派发剩余待执行任务");

        BatchInfo resumed = batchService.resume(1L, batch.id());
        assertEquals(4, resumed.status(), "续跑回执行中(4)");
        assertNotNull(execTaskService.claim(exId), "续跑后断点继续派发");
    }

    @Test
    void test_i6_illegal_transitions_rejected_with_human_message() {
        long pid = createProject("I6A3");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "用例甲", "web");
        approve(c1);
        withScript(c1);
        BatchInfo draft = batchService.create(1L, createInput(pid, "草稿批", env, List.of(c1)));
        ServiceException e1 = assertThrows(ServiceException.class, () -> batchService.pause(1L, draft.id()));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, e1.getCode());
        assertTrue(e1.getMessage().contains("执行中"), "人话告知前置态：草稿不可暂停：" + e1.getMessage());

        long done = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, schedule_type)"
                        + " VALUES (?, ?, 'B-DONE', '完成批', 6, 1, 0) RETURNING id", Long.class, pid, env);
        ServiceException e2 = assertThrows(ServiceException.class, () -> batchService.pause(1L, done));
        assertTrue(e2.getMessage().contains("已完成"), "终态点名：完成批不可暂停：" + e2.getMessage());
        ServiceException e3 = assertThrows(ServiceException.class, () -> batchService.resume(1L, done));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, e3.getCode());

        long cancelled = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, env_id, batch_no, name, status, type, schedule_type)"
                        + " VALUES (?, ?, 'B-CAN', '取消批', 8, 1, 0) RETURNING id", Long.class, pid, env);
        ServiceException e4 = assertThrows(ServiceException.class, () -> batchService.resume(1L, cancelled));
        assertTrue(e4.getMessage().contains("取消") && e4.getMessage().contains("重建"),
                "取消批不可续跑人话走重建：" + e4.getMessage());
    }

    @Test
    void test_i6_cancel_marks_unexecuted_and_keeps_completed() {
        long pid = createProject("I6A4");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 3, "ex-i6a4");
        BatchInfo cancelled = batchService.cancel(1L, batch.id());
        assertEquals(8, cancelled.status(), "已取消=8");
        assertEquals(0, count("SELECT count(*) FROM tp_exec_task WHERE batch_id = " + batch.id()
                + " AND status = 1"), "待派发任务全部取消不残留");
        long pending = count("SELECT count(*) FROM tp_batch_case WHERE batch_id = " + batch.id()
                + " AND result = 4");
        assertEquals(3, pending, "未执行成员打标保持（result=4 未执行）");
        assertThrows(ServiceException.class, () -> batchService.resume(1L, batch.id()),
                "终态不可续跑");
        assertThrows(ServiceException.class, () -> batchService.pause(1L, batch.id()),
                "终态不可暂停");
    }

    @Test
    void test_i6_skip_case_marks_manual_skip_and_next_dispatches() {
        long pid = createProject("I6A5");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6a5");
        String msg = batchService.skipCase(1L, batch.id());
        assertTrue(msg.contains("跳过"), "人话结果：" + msg);
        assertEquals(1, count("SELECT count(*) FROM tp_exec_task WHERE batch_id = " + batch.id()
                + " AND status = 6"), "执行中任务标记已跳过(6)");
        assertEquals(1, count("SELECT count(*) FROM tp_batch_case WHERE batch_id = " + batch.id()
                + " AND manual_skip = 1 AND result = 3"), "明细手动跳过打标");
        long ex2 = executorService.register(new com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput(
                "ex-i6a5b", null, null, "web", "chromium", 2, "1.0.0", 0));
        assertNotNull(execTaskService.claim(ex2), "跳过后下一条继续派发");
    }

    @Test
    void test_i6_rerun_failed_only_failures_and_running_rejected() {
        long pid = createProject("I6A6");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "失败用例", "web");
        long c2 = createCase(pid, "跳过用例", "web");
        approve(c1, c2);
        withScript(c1);
        withScript(c2);
        BatchInfo batch = batchService.create(1L, createInput(pid, "重跑批", env, List.of(c1, c2)));
        batchService.start(1L, batch.id());
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4 WHERE batch_id = ? AND case_id = ?",
                batch.id(), c1);
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 6 WHERE batch_id = ? AND case_id = ?",
                batch.id(), c2);
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 4 WHERE id = ?", batch.id());
        ServiceException running = assertThrows(ServiceException.class,
                () -> batchService.rerunFailed(1L, batch.id()));
        assertEquals(BizErrorCode.STATE_MACHINE_REJECT, running.getCode());
        assertTrue(running.getMessage().contains("执行中"), "人话：" + running.getMessage());
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 7 WHERE id = ?", batch.id());
        int created = batchService.rerunFailed(1L, batch.id());
        assertEquals(1, created, "仅失败集重跑");
        assertEquals(3, taskCount(batch.id()), "新建 task 不覆盖历史（原 2 终态 + 新 1 待派发）");
        assertEquals(1, count("SELECT count(*) FROM tp_exec_task WHERE batch_id = " + batch.id()
                + " AND status = 1 AND case_id = " + c1));
        BatchInfo after = batchService.get(batch.id());
        assertEquals(2, after.status(), "重跑后批次回待执行(2)");
    }

    @Test
    void test_i6_queue_pos_atomic_and_cancel_releases_slot() {
        long pid = createProject("I6A7");
        long env = createEnv(pid, "测试环境", "web");
        List<Runnable> starts = new java.util.ArrayList<>();
        List<Long> batchIds = new java.util.ArrayList<>();
        for (int i = 1; i <= 4; i++) {
            long c = createCase(pid, "排队用例" + i, "web");
            approve(c);
            withScript(c);
            BatchInfo b = batchService.create(1L, createInput(pid, "排队批" + i, env, List.of(c)));
            batchIds.add(b.id());
            long id = b.id();
            starts.add(() -> batchService.start(1L, id));
        }
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(4);
        starts.forEach(pool::submit);
        pool.shutdown();
        try {
            assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS), "并发启动完成");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
        List<Integer> positions = batchIds.stream().map(id -> batchService.get(id).queuePos()).toList();
        assertEquals(4, new java.util.HashSet<>(positions).size(),
                "并发启动 queue_pos 互不重复（原子取位）：" + positions);
        batchService.cancel(1L, batchIds.get(indexOfPos(batchIds, 1)));
        long c9 = createCase(pid, "后到用例", "web");
        approve(c9);
        withScript(c9);
        BatchInfo later = batchService.create(1L, createInput(pid, "后到批", env, List.of(c9)));
        batchService.start(1L, later.id());
        assertEquals(4, batchService.get(later.id()).queuePos(),
                "取消队首后其任务出队，后到批次排队位前移");
    }

    
    private int indexOfPos(List<Long> batchIds, int target) {
        for (int i = 0; i < batchIds.size(); i++) {
            if (Integer.valueOf(target).equals(batchService.get(batchIds.get(i)).queuePos())) {
                return i;
            }
        }
        throw new AssertionError("无 queue_pos=" + target + " 的批次");
    }


    
    private long createMember(long projectId, String username) {
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, username, username);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, uid);
        return uid;
    }

    
    private void claimAndSubmit(long executorId, String result) {
        ClaimTaskView claimed = execTaskService.claim(executorId);
        assertNotNull(claimed, "有待派发任务可领（result=" + result + "）");
        execTaskService.submitResult(new ExecResultInput(
                claimed.taskId(), claimed.claimToken(), result,
                null, null, null, null, 100, null));
    }

    
    private void submitRunning(long batchId, String result) {
        java.util.Map<String, Object> t = jdbcTemplate.queryForMap(
                "SELECT id, claim_token FROM tp_exec_task WHERE batch_id = ? AND status = 2"
                        + " ORDER BY id LIMIT 1", batchId);
        execTaskService.submitResult(new ExecResultInput(
                ((Number) t.get("id")).longValue(), (String) t.get("claim_token"), result,
                null, null, null, null, 100, null));
    }

    
    private long claimedExecutor(long batchId) {
        return jdbcTemplate.queryForObject("SELECT executor_id FROM tp_exec_task"
                + " WHERE batch_id = ? AND executor_id IS NOT NULL LIMIT 1", Long.class, batchId);
    }

    @Test
    void test_i6_final_batch_all_pass_to_done() {
        long pid = createProject("I6B1");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6b1");
        assertEquals(4, batchService.get(batch.id()).status(), "领取后=执行中(4)");
        submitRunning(batch.id(), "pass");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        BatchInfo after = batchService.get(batch.id());
        assertEquals(6, after.status(), "全部通过批次应=已完成(6)（当前缺 4→6 写入路径=RED）");
        List<BatchCaseInfo> rows = batchService.listCases(batch.id());
        assertEquals(2, rows.size());
        assertTrue(rows.stream().allMatch(r -> r.result() == 1),
                "全部通过明细 result 应回填 1=通过（当前 submitResult 零回写 tp_batch_case=RED）");
    }

    @Test
    void test_i6_final_batch_has_fail_to_partial() {
        long pid = createProject("I6B2");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6b2");
        submitRunning(batch.id(), "fail");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        BatchInfo after = batchService.get(batch.id());
        assertEquals(7, after.status(), "有失败批次应=部分失败(7)（RED）");
        assertDoesNotThrow(() -> batchService.rerunFailed(1L, batch.id()),
                "has_fail 生产推进后 rerunFailed {7}→2 应可达");
        List<BatchCaseInfo> failRows = batchService.listCases(batch.id());
        assertEquals(1, failRows.stream().filter(r -> r.result() == 2).count(),
                "失败明细应回填 1 行 result=2");
        assertEquals(1, failRows.stream().filter(r -> r.result() == 1).count(),
                "通过明细应回填 1 行 result=1");
    }

    @Test
    void test_i6_final_running_batch_not_touched() {
        long pid = createProject("I6B3");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 3, "ex-i6b3");
        submitRunning(batch.id(), "pass");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        assertEquals(4, batchService.get(batch.id()).status(),
                "还有 1 条待派发未领取→保持执行中(4)，不得提前 finalize");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        assertEquals(6, batchService.get(batch.id()).status(),
                "最后一条回传后批次全终态→应推进已完成(6)（RED）");
    }

    @Test
    void test_i6_skip_last_task_finalizes_batch() {
        long pid = createProject("I6B5");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 1, "ex-i6b5");
        String msg = batchService.skipCase(1L, batch.id());
        assertTrue(msg.contains("跳过"), "人话结果：" + msg);
        BatchInfo after = batchService.get(batch.id());
        assertEquals(6, after.status(),
                "skipCase 终结最后一条任务后批次应即时收口已完成(6)（RED：当前停留执行中 4）");
        assertNotNull(after.finishedAt(), "收口应落 finished_at");
        assertEquals(1, count("SELECT count(*) FROM tp_batch_case WHERE batch_id = " + batch.id()
                + " AND manual_skip = 1 AND result = 3"), "跳过明细打标保持（不因收口丢失）");
    }

    @Test
    void test_i6_skip_after_pass_finalizes_batch_done() {
        long pid = createProject("I6B6");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6b6");
        submitRunning(batch.id(), "pass");
        assertNotNull(execTaskService.claim(claimedExecutor(batch.id())),
                "task2 领取进入执行中(2)——skipCase 仅作用于执行中任务");
        batchService.skipCase(1L, batch.id());
        BatchInfo after = batchService.get(batch.id());
        assertEquals(6, after.status(),
                "回传+跳过混合全终态后批次应收口(6)（RED：skip 无触发源停 4）");
        List<BatchCaseInfo> rows = batchService.listCases(batch.id());
        assertEquals(1, rows.stream().filter(r -> r.result() == 1).count(), "通过明细 result=1");
        assertEquals(1, rows.stream().filter(r -> r.result() == 3).count(), "跳过明细 result=3");
    }

    @Test
    void test_i6_skip_with_pending_tasks_does_not_finalize() {
        long pid = createProject("I6B7");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 3, "ex-i6b7");
        batchService.skipCase(1L, batch.id());
        assertEquals(4, batchService.get(batch.id()).status(),
                "尚有 2 条待派发→保持执行中(4)，不得提前 finalize");
    }

    @Test
    void test_i6_member_cannot_control_batch() {
        long pid = createProject("I6B4");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-i6b4");
        long memberId = createMember(pid, "member-i6b4");
        for (String action : List.of("pause", "resume", "cancel", "skip")) {
            ServiceException e = assertThrows(ServiceException.class, switch (action) {
                case "pause" -> () -> batchService.pause(memberId, batch.id());
                case "resume" -> () -> batchService.resume(memberId, batch.id());
                case "cancel" -> () -> batchService.cancel(memberId, batch.id());
                default -> () -> batchService.skipCase(memberId, batch.id());
            }, action + " 成员应被拒");
            assertEquals(BizErrorCode.FORBIDDEN, e.getCode(), action + " 应 403：" + e.getMessage());
            assertTrue(e.getMessage().contains("负责人"), action + " 人话点名负责人：" + e.getMessage());
        }
        jdbcTemplate.update("UPDATE tp_exec_batch SET status = 7 WHERE id = ?", batch.id());
        assertDoesNotThrow(() -> batchService.rerunFailed(memberId, batch.id()),
                "重跑失败集=项目成员级，member 不应 403");
    }


    @Test
    void test_i9_quick_execute_creates_temp_batch_started() {
        long pid = createProject("I9A");
        long env = createEnv(pid, "测试环境", "web");
        long c1 = createCase(pid, "登录冒烟", "web");
        approve(c1);
        withScript(c1);

        BatchStartResult result = batchService.quickExecute(1L, c1, env);
        assertTrue(result.batchId() > 0, "临时批自动创建并返回 id");
        assertEquals(2, result.status(), "返回即已启动（待执行 2，claim 后由 T135 链推 4）");
        assertEquals("executing", result.statusKey(), "复用 start 响应契约（statusKey 并存口径）");
        assertNotNull(result.queuePos(), "排队位即时回显");
        assertTrue(result.message().contains("1 个执行任务"), "message 人话计数：" + result.message());

        Integer type = jdbcTemplate.queryForObject(
                "SELECT type FROM tp_exec_batch WHERE id = ?", Integer.class, result.batchId());
        assertEquals(2, type, "快速执行批=临时批 type=2（01 权威：1 正式/2 临时）");
        Long tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE batch_id = ?", Long.class, result.batchId());
        assertEquals(1L, tasks, "有脚本用例建 1 任务");
        BatchInfo detail = batchService.get(result.batchId());
        assertEquals(2, detail.status(), "详情可查（跳转 /batches/{id} 依据）");
        assertEquals(1, detail.caseCount());
    }

    @Test
    void test_i9_temp_batch_excluded_from_list_but_visible_in_detail() {
        long pid = createProject("I9B");
        long env = createEnv(pid, "测试环境", "web");
        long formalCase = createCase(pid, "正式批用例", "web");
        approve(formalCase);
        withScript(formalCase);
        long quickCase = createCase(pid, "快速执行用例", "web");
        approve(quickCase);
        withScript(quickCase);

        BatchInfo formal = batchService.create(1L, createInput(pid, "正式批", env,
                List.of(formalCase)));
        batchService.start(1L, formal.id());
        BatchStartResult quick = batchService.quickExecute(1L, quickCase, env);

        List<Long> listed = batchService.list(new BatchQuery(pid, null, null, 1, 20)).list()
                .stream().map(BatchInfo::id).toList();
        assertTrue(listed.contains(formal.id()), "正式批在列表");
        assertTrue(!listed.contains(quick.batchId()), "临时批不进默认视图（IT-BATCH-08）");
        assertDoesNotThrow(() -> batchService.get(quick.batchId()), "详情仍可查（结果可追溯）");
    }

    @Test
    void test_i9_quick_execute_unevaluated_and_no_script_precheck_reuse() {
        long pid = createProject("I9C");
        long env = createEnv(pid, "测试环境", "web");
        long notReviewed = createCase(pid, "未评审用例", "web");
        jdbcTemplate.update("UPDATE tp_test_case SET status = 1 WHERE id = ?", notReviewed);
        long noScript = createCase(pid, "无脚本用例", "web");
        approve(noScript);

        BatchStartResult result = assertDoesNotThrow(() ->
                batchService.quickExecute(1L, notReviewed, env));
        assertEquals(1, result.skipCases().size(), "未评审用例进跳过清单（镜像 start N6 语义）");
        assertEquals(notReviewed, result.skipCases().get(0).caseId(), "点名该用例");
        assertTrue(result.message().contains("未通过评审"), "message 人话：" + result.message());

        BatchStartResult noScriptResult = batchService.quickExecute(1L, noScript, env);
        assertTrue(noScriptResult.message().contains("无脚本"),
                "无脚本=0 任务+人话计数（start 同口径）：" + noScriptResult.message());
    }

    @Test
    void test_i9_quick_execute_not_blocked_by_formal_backlog() {
        long pid = createProject("I9D");
        long env = createEnv(pid, "测试环境", "web");
        List<Long> backlogCases = new java.util.ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            long c = createCase(pid, "积压用例" + i, "web");
            approve(c);
            withScript(c);
            backlogCases.add(c);
        }
        BatchInfo formal = batchService.create(1L, createInput(pid, "积压正式批", env, backlogCases));
        batchService.start(1L, formal.id());
        long quickCase = createCase(pid, "快速冒烟", "web");
        approve(quickCase);
        withScript(quickCase);

        BatchStartResult quick = assertDoesNotThrow(() -> batchService.quickExecute(1L, quickCase, env),
                "正式批积压不阻断快速执行（无配额拦截）");
        assertNotNull(quick.queuePos(), "排队位给出（排在积压之后）");
        assertEquals(2, quick.status(), "临时批正常入队待执行");
    }


    
    private long insertUser(String username) {
        return jdbcTemplate.queryForObject(
                "INSERT INTO tp_user (username, password_hash, display_name, role, status) "
                        + "VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id",
                Long.class, username, username);
    }

    
    private List<java.util.Map<String, Object>> batchFailedNotifs(long batchId) {
        return jdbcTemplate.queryForList(
                "SELECT user_id, project_id, title, content, dedup_key FROM tp_notification"
                        + " WHERE event_type = 'BATCH_FAILED' AND dedup_key LIKE ?",
                "BATCH_FAILED:" + batchId + ":%");
    }

    
    private void setTestOwner(long pid, Long testOwnerId) {
        projectService.update(pid, new ProjectUpdateInput("批次项目", "zh-CN", "P1", null,
                null, testOwnerId, null, null, null, null, null));
    }

    @Test
    void batchFailed_notifiesTestOwner_exactlyOnce() {
        long pid = createProject("BF1");
        long testOwner = insertUser("bf1-testowner");
        setTestOwner(pid, testOwner);
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-bf1");
        submitRunning(batch.id(), "fail");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        assertEquals(7, batchService.get(batch.id()).status(), "前置：批次已推进部分失败(7)");

        List<java.util.Map<String, Object>> rows = batchFailedNotifs(batch.id());
        assertEquals(1, rows.size(), "部分失败批次应恰发 1 条 BATCH_FAILED 通知");
        java.util.Map<String, Object> row = rows.get(0);
        assertEquals(testOwner, ((Number) row.get("user_id")).longValue(), "收件人=test_owner");
        assertEquals(pid, ((Number) row.get("project_id")).longValue(), "通知挂项目");
        assertTrue(String.valueOf(row.get("title")).contains("批次"), "标题人话含「批次」: " + row.get("title"));
        String content = String.valueOf(row.get("content"));
        assertTrue(content.contains("失败"), "内容含失败信息: " + content);
        assertTrue(content.contains("控制台用例"), "内容信息完整（含用例名，不截断）: " + content);
    }

    @Test
    void batchFailed_withoutTestOwner_fallsBackToProjectOwners() {
        long pid = createProject("BF2");
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-bf2");
        submitRunning(batch.id(), "fail");
        claimAndSubmit(claimedExecutor(batch.id()), "fail");

        List<java.util.Map<String, Object>> rows = batchFailedNotifs(batch.id());
        assertEquals(1, rows.size(), "未设 test_owner 时回退项目负责人恰 1 条");
        assertEquals(1L, ((Number) rows.get(0).get("user_id")).longValue(),
                "回退收件人=项目 role=2 负责人（create 人 user_id=1）");
    }

    @Test
    void batchAllPass_sendsNoBatchFailed() {
        long pid = createProject("BF3");
        long testOwner = insertUser("bf3-testowner");
        setTestOwner(pid, testOwner);
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-bf3");
        submitRunning(batch.id(), "pass");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        assertEquals(6, batchService.get(batch.id()).status(), "前置：批次已完成(6)");

        assertEquals(0, batchFailedNotifs(batch.id()).size(), "全过批次零 BATCH_FAILED 通知");
    }

    @Test
    void batchFailed_lateRetrigger_notifiesOnce() {
        long pid = createProject("BF4");
        long testOwner = insertUser("bf4-testowner");
        setTestOwner(pid, testOwner);
        long env = createEnv(pid, "测试环境", "web");
        BatchInfo batch = startedAndClaimed(pid, env, 2, "ex-bf4");
        submitRunning(batch.id(), "fail");
        claimAndSubmit(claimedExecutor(batch.id()), "pass");
        assertEquals(1, batchFailedNotifs(batch.id()).size(), "首触发恰 1 条");

        batchFinalizeSupport.onCaseSkipped(batch.id());
        assertEquals(1, batchFailedNotifs(batch.id()).size(), "收口段重触发=0 行推进，通知不重复");
    }
}
