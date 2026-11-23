package com.sixbean.testpike.core.batch.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.api.dto.BatchCreateInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInfo;
import com.sixbean.testpike.core.batch.api.dto.BatchStartResult;
import com.sixbean.testpike.core.batch.api.dto.BadgeView;
import com.sixbean.testpike.core.batch.api.dto.EnvOverrideInput;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
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

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class,
        properties = "testpike.prod-blacklist=black.example.com")
class BatchCiContractTest {

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
    private JdbcTemplate jdbcTemplate;

    private long caseSeq;

    // Fixture-scoped teardown (truncate-ratchet discipline, same shape as the
    // appauto dispatch suite): this suite's projects live in the name families
    // below (codes CIB*/CIS*/CIO*, where CIO also covers the CION* variants).
    // The FK child-first sweep covers tp_script/tp_test_case/tp_project_env/
    // tp_user_project/tp_exec_task/tp_exec_batch under them plus the default
    // project-keyed tables (audit log, notifications, batches). tp_batch_case
    // has no FK and no project column, so this family's rows purge first
    // through the still-present batch rows. tp_batch_template/tp_user/
    // tp_executor rows are never created here — the old CASCADE wiped shipped
    // rows, the sweep leaves them alone.
    private static final List<String> FIXTURE_PROJECT_NAME_PREFIXES =
            List.of("项目CIB", "项目CIS", "项目CIO");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_batch_case WHERE batch_id IN"
                + " (SELECT b.id FROM tp_exec_batch b"
                + "  JOIN tp_project p ON p.id = b.project_id"
                + "  WHERE p.name LIKE ? OR p.name LIKE ? OR p.name LIKE ?)",
                "项目CIB%", "项目CIS%", "项目CIO%");
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, List.of(), FIXTURE_PROJECT_NAME_PREFIXES);
    }

    private long createProject(String code) {
        return projectService.create(1L, new ProjectCreateInput("项目" + code, code, "zh-CN", "P1", null));
    }

    private long createEnv(long pid, String name) {
        return projectEnvService.create(pid, new ProjectEnvInput(name, 2,
                "https://" + name + ".example.com", null, "web", "chromium", "v1.0.0"));
    }

    
    private long readyCase(long pid, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, title, "前置", "预期",
                1, 2, "web", 3, 2, 1, null, List.of()));
        jdbcTemplate.update("UPDATE tp_test_case SET status = 3 WHERE id = ?", info.id());
        scriptService.save(new ScriptSaveInput(info.id(), "v2",
                "{\"caseId\":" + info.id() + ",\"steps\":[]}", 2));
        return info.id();
    }

    
    private BatchInfo draftBatch(long pid, long env) {
        long c = readyCase(pid, "CI用例-" + (++caseSeq));
        return batchService.create(1L, new BatchCreateInput(pid, "CI批-" + caseSeq, env,
                List.of(c), null));
    }

    private String badgeTokenOf(long batchId) {
        return jdbcTemplate.queryForObject(
                "SELECT badge_token::text FROM tp_exec_batch WHERE id = ?", String.class, batchId);
    }

    private String ciRunAuditJson(long batchId) {
        List<String> rows = jdbcTemplate.query(
                "SELECT after_json::text FROM tp_audit_log WHERE action = 'CI_RUN' AND object_id = ?",
                (rs, n) -> rs.getString(1), String.valueOf(batchId));
        return rows.isEmpty() ? null : rows.get(0);
    }


    @Test
    void test_i8_badge_view_rate_and_phrase() {
        long pid = createProject("CIB1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = batchService.create(1L, new BatchCreateInput(pid, "CI徽章批", env,
                List.of(readyCase(pid, "CI徽章用例1"), readyCase(pid, "CI徽章用例2")), null));
        batchService.start(1L, batch.id());
        String token = badgeTokenOf(batch.id());
        assertNotNull(token, "V59 默认 gen_random_uuid 应非空");

        BadgeView fresh = batchService.getByBadgeToken(token);
        assertEquals(batch.id(), fresh.batchId());
        assertEquals(2, fresh.status());
        assertEquals("待执行", fresh.statusLabel());
        assertNull(fresh.passRate());
        assertEquals("暂无终态任务", fresh.passRatePhrase());

        jdbcTemplate.update("UPDATE tp_exec_task SET status = 3 WHERE id = "
                + "(SELECT min(id) FROM tp_exec_task WHERE batch_id = ?)", batch.id());
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 4 WHERE id > "
                + "(SELECT min(id) FROM tp_exec_task WHERE batch_id = ?)", batch.id());
        BadgeView done = batchService.getByBadgeToken(token);
        assertEquals(0, done.passRate().compareTo(new java.math.BigDecimal("0.5")));
        assertEquals("通过率 50.0%", done.passRatePhrase());
    }

    @Test
    void test_i8_badge_unknown_token_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.getByBadgeToken(UUID.randomUUID().toString()));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("徽章不存在"), ex.getMessage());
    }

    @Test
    void test_i8_badge_not_uuid_400() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.getByBadgeToken("not-a-uuid"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    
    @Test
    void test_i8_badge_by_batch_id_same_source() {
        long pid = createProject("CIS1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = draftBatch(pid, env);
        batchService.start(1L, batch.id());
        jdbcTemplate.update("UPDATE tp_exec_task SET status = 3 WHERE batch_id = ?", batch.id());
        String token = badgeTokenOf(batch.id());

        BadgeView byToken = batchService.getByBadgeToken(token);
        BadgeView byId = batchService.badgeByBatchId(batch.id());
        assertEquals(byToken.batchId(), byId.batchId());
        assertEquals(byToken.status(), byId.status());
        assertEquals(byToken.statusLabel(), byId.statusLabel());
        assertEquals(0, byToken.passRate().compareTo(byId.passRate()), "两链路通过率必同值（PassRateSql 单点）");
    }

    @Test
    void test_i8_badge_by_batch_id_unknown_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.badgeByBatchId(999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertTrue(ex.getMessage().contains("批次不存在"), ex.getMessage());
    }


    @Test
    void test_ci_start_url_override_written_task_and_audited() {
        long pid = createProject("CIO1");
        long env = createEnv(pid, "测试");
        BatchInfo batch = draftBatch(pid, env);
        String envUrlBefore = jdbcTemplate.queryForObject(
                "SELECT base_url FROM tp_project_env WHERE id = ?", String.class, env);
        long envRowsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE project_id = ?", Long.class, pid);

        BatchStartResult result = batchService.start(1L, batch.id(),
                new EnvOverrideInput("https://ci-target.example.com", "acct-ref-1"));
        assertEquals(2, result.status());

        List<String> urls = jdbcTemplate.query(
                "SELECT env_base_url FROM tp_exec_task WHERE batch_id = ?",
                (rs, n) -> rs.getString(1), batch.id());
        assertEquals(List.of("https://ci-target.example.com"), urls);
        assertEquals(envUrlBefore, jdbcTemplate.queryForObject(
                "SELECT base_url FROM tp_project_env WHERE id = ?", String.class, env));
        assertEquals(envRowsBefore, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_project_env WHERE project_id = ?",
                Long.class, pid).longValue());

        String audit = ciRunAuditJson(batch.id());
        assertNotNull(audit, "覆盖启动必须另记 CI_RUN 审计");
        assertTrue(audit.contains("ci-target.example.com"), audit);
        assertTrue(audit.contains("acct-ref-1"), audit);

        Long startAuditRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_audit_log WHERE action = 'batch_start' AND object_id = ?",
                Long.class, String.valueOf(batch.id()));
        assertTrue(startAuditRows <= 1, "batch_start 审计双挂：" + startAuditRows + " 行");
    }

    @Test
    void test_ci_start_all_null_overrides_equals_noop() {
        long pid = createProject("CIO2");
        long env = createEnv(pid, "测试");
        BatchInfo batch = draftBatch(pid, env);
        batchService.start(1L, batch.id(), new EnvOverrideInput(null, null));
        String url = jdbcTemplate.queryForObject(
                "SELECT env_base_url FROM tp_exec_task WHERE batch_id = ?", String.class, batch.id());
        String envUrl = jdbcTemplate.queryForObject(
                "SELECT base_url FROM tp_project_env WHERE id = ?", String.class, env);
        assertEquals(envUrl, url, "全 null 覆盖=任务快照仍取环境 base_url");
        assertNull(ciRunAuditJson(batch.id()), "全 null=等同无覆盖，禁记 CI_RUN");
    }

    @Test
    void test_ci_start_override_cannot_bypass_prod_blacklist() {
        long pid = createProject("CIO3");
        long env = createEnv(pid, "测试");
        BatchInfo batch = draftBatch(pid, env);
        ServiceException ex = assertThrows(ServiceException.class, () -> batchService.start(1L, batch.id(),
                new EnvOverrideInput("https://black.example.com", null)));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode());
        assertTrue(ex.getMessage().contains("黑名单"), ex.getMessage());
    }


    @Test
    void test_it_i8_04_override_validation_negatives() {
        long pid = createProject("CION");
        long env = createEnv(pid, "测试");
        List<String> badUrls = Arrays.asList(
                "https://" + "a".repeat(600),              // >512
                "https://ok.example.com\nnext",
                "ftp://files.example.com",
                "https://ok.example.com/{{ENV:pass}}",
                "https://ok.example.com/${jndi:x}");
        for (String bad : badUrls) {
            BatchInfo b = draftBatch(pid, env);
            ServiceException ex = assertThrows(ServiceException.class,
                    () -> batchService.start(1L, b.id(), new EnvOverrideInput(bad, null)),
                    "应拒绝覆盖址: " + bad.substring(0, Math.min(40, bad.length())));
            assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        }
        assertRefRejected(new EnvOverrideInput("  ", null));
        assertRefRejected(new EnvOverrideInput(null, "ref-" + "a".repeat(200)));
        assertRefRejected(new EnvOverrideInput(null, "{{acct}}"));
    }

    
    private void assertRefRejected(EnvOverrideInput overrides) {
        long pid = createProject("CION" + (++caseSeq));
        long env = createEnv(pid, "测试");
        BatchInfo b = draftBatch(pid, env);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> batchService.start(1L, b.id(), overrides));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }
}
