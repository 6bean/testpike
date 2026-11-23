package com.sixbean.testpike.core.apitest.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ApiTestDispatchTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Autowired
    private ApiRunService apiRunService;

    @Autowired
    private ApiRequestService apiRequestService;

    @Autowired
    private ApiCollectionService apiCollectionService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CredentialService credentialService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final java.util.List<Long> projectIds =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach
    @AfterEach
    void sweepDispatchProjectFixtures() {
        ProjectFixtureRig.sweep(jdbcTemplate, projectIds);
    }

    private long createDispatchProject(String code) {
        return ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                projectIds, "项目" + code, code);
    }

    private long createEnv(long projectId, String baseUrl, String configJson) {
        return jdbcTemplate.queryForObject("INSERT INTO tp_project_env (project_id, name, base_url, "
                + "config_json) VALUES (?, ?, ?, ?::jsonb) RETURNING id", Long.class,
                projectId, "env-" + baseUrl, baseUrl, configJson);
    }

    private ApiRequestInfo seedRequest(long projectId) {
        ApiCollectionInfo root = apiCollectionService.create(1L,
                new CollectionSaveInput(projectId, 0L, "订单域", null));
        // Secret slots carry a vault reference (save-gate contract W1c); the real
        // value is registered in the credential vault and injected at dispatch.
        credentialService.create(projectId, new CredentialInput(
                "订单令牌", "tk", "t-1", null));
        return apiRequestService.create(1L, new RequestSaveInput(root.id(), "查订单", "GET",
                "/api/v1/orders/{id}", "[{\"name\":\"X-Test\",\"value\":\"1\"}]", "{\"a\":1}",
                "{\"type\":\"bearer\",\"token\":\"@cred/tk\"}",
                "[{\"type\":\"status\",\"expect\":200},{\"type\":\"body\",\"path\":\"$.id\",\"op\":\"exact\",\"expect\":\"42\"}]"));
    }

    @Test
    void test_create_run_with_env_dispatches_api_test_run_task() throws Exception {
        long pid = createDispatchProject("W1D1");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "http://order-a.test",
                "{\"params\":{\"TOKEN\":\"plain-secret\"}}");

        ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(req.id(), envId));

        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE task_type = 4 AND project_id = ?",
                Long.class, pid);
        assertTrue(taskId != null, "带环境建运行必须派发 task_type=4 执行任务");
        Integer caseId = jdbcTemplate.queryForObject(
                "SELECT case_id FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(0, caseId, "case_id=0 哨兵（tool/explore 先例同构，零新通道）");
        String status = jdbcTemplate.queryForObject(
                "SELECT status::text FROM tp_exec_task WHERE id = ?", String.class, taskId);
        assertEquals("1", status, "新单=待派发 1");

        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE id = ?", String.class, taskId);
        JsonNode env = MAPPER.readTree(actionJson);
        assertEquals("api-test-run", env.path("kind").asText());
        assertEquals(run.id(), env.path("runId").asLong(), "信封回带 runId（回传链路映射锚）");
        assertEquals("GET", env.path("method").asText());
        assertEquals("/api/v1/orders/{id}", env.path("urlPath").asText());
        assertEquals("http://order-a.test", env.path("envBaseUrl").asText());
        assertEquals("plain-secret", env.path("envVars").path("TOKEN").asText(),
                "envVars 经单源解密件下发");
        assertTrue(env.path("assertions").isArray() && env.path("assertions").size() == 2,
                "断言组从 tp_apitest_request jsonb 原文反组");
        assertEquals(200, env.path("assertions").get(0).path("expect").asInt(),
                "反组保结构（非字符串化二次包装）");
        assertTrue(env.path("headers").path("X-Test").asText().equals("1"),
                "headers 由存储行数组形归一为信封对象形（03 §2.3.2，执行器 dict 消费）");
        assertEquals(1, env.path("body").path("a").asInt());
        assertEquals("bearer", env.path("auth").path("type").asText());
        assertEquals("t-1", env.path("auth").path("token").asText(),
                "@cred/ reference resolves to the vault value at the assembly point");
        assertTrue(env.path("execPolicy").isObject() && env.path("execPolicy").has("allowHosts"),
                "execPolicy 同 claim 口径下发（SSRF 双闸消费）");
        assertEquals("strict", env.path("tlsPolicy").path("mode").asText(),
                "无 TLS 档案=strict 失败安全档");
        assertTrue(env.path("timeoutMs").asLong() > 0, "timeoutMs 必带（禁无预算挂死）");
    }

    @Test
    void test_create_run_without_env_creates_no_task() {
        long pid = createDispatchProject("W1D2");
        ApiRequestInfo req = seedRequest(pid);

        ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(req.id(), null));

        assertEquals(0, run.status(), "无环境=先建后选，停待执行 0");
        Integer tasks = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE project_id = ?", Integer.class, pid);
        assertEquals(0, tasks, "无环境不建派发单（目标域名白名单未定，禁派发）");
    }

    @Test
    void test_envelope_tls_policy_from_env_profile_custom_ca() throws Exception {
        long pid = createDispatchProject("W1D3");
        ApiRequestInfo req = seedRequest(pid);
        createEnv(pid, "https://order-b.test",
                "{\"tls\":{\"mode\":\"custom_ca\",\"ca_pem\":\"-----BEGIN CERTIFICATE-----\\nAAA\\n-----END CERTIFICATE-----\"}}");

        Long envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? ORDER BY id LIMIT 1",
                Long.class, pid);
        ApiRunInfo run = apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode env = MAPPER.readTree(actionJson);
        assertEquals(run.id(), env.path("runId").asLong(), "末单信封对应末次运行");
        assertEquals("custom_ca", env.path("tlsPolicy").path("mode").asText(),
                "环境档案 TLS 决议经 EnvTlsSupport 单源件下发");
        assertTrue(env.path("tlsPolicy").path("caPem").asText().contains("BEGIN CERTIFICATE"));
    }

    @Test
    void test_envelope_vars_seed_from_collection_pool() throws Exception {
        long pid = createDispatchProject("W1D4");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "http://order-d.test", "{}");

        Long collectionId = jdbcTemplate.queryForObject(
                "SELECT collection_id FROM tp_apitest_request WHERE id = ?",
                Long.class, req.id());
        jdbcTemplate.update("UPDATE tp_apitest_collection SET vars_json = ?::jsonb WHERE id = ?",
                "{\"oid\":\"88\"}", collectionId);
        apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode env = MAPPER.readTree(actionJson);
        assertEquals("88", env.path("vars").path("oid").asText(),
                "集合级变量池经信封 vars 键下发（执行器 resolve_vars 消费）");
    }

    @Test
    void test_envelope_global_seed_with_collection_priority() throws Exception {
        long pid = createDispatchProject("W1D6");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "http://order-f.test", "{}");

        jdbcTemplate.update("INSERT INTO tp_apitest_global_var (project_id, var_key, var_value) "
                + "VALUES (?, 'shared', 'from-global'), (?, 'only-global', 'g-1')",
                pid, pid);
        Long collectionId = jdbcTemplate.queryForObject(
                "SELECT collection_id FROM tp_apitest_request WHERE id = ?",
                Long.class, req.id());
        jdbcTemplate.update("UPDATE tp_apitest_collection SET vars_json = ?::jsonb WHERE id = ?",
                "{\"shared\":\"from-collection\"}", collectionId);
        apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode env = MAPPER.readTree(actionJson);
        assertEquals("from-collection", env.path("vars").path("shared").asText(),
                "同名键集合级种子优先于 global");
        assertEquals("g-1", env.path("vars").path("only-global").asText(),
                "global 独有键以裸名并入信封种子");
    }

    @Test
    void test_envelope_no_vars_key_when_pool_empty() throws Exception {
        long pid = createDispatchProject("W1D5");
        ApiRequestInfo req = seedRequest(pid);
        long envId = createEnv(pid, "http://order-e.test", "{}");

        apiRunService.create(1L, new RunCreateInput(req.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode env = MAPPER.readTree(actionJson);
        assertTrue(env.path("vars").isMissingNode(),
                "空池不出 vars 键=旧执行器信封形状零回归（尾追兼容姿势）");
    }

    /**
 * orchestration rows ride the envelope as precondition/loop keys.
 * A legacy request (no rows) must keep the byte-old shape — keys absent.
 */
    @Test
    void test_envelope_orchestration_keys_only_when_rows_present() throws Exception {
        long pid = createDispatchProject("W1D7");
        ApiRequestInfo legacy = seedRequest(pid);
        long envId = createEnv(pid, "http://order-g.test", "{}");

        apiRunService.create(1L, new RunCreateInput(legacy.id(), envId));
        String actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode legacyEnv = MAPPER.readTree(actionJson);
        assertTrue(legacyEnv.path("precondition").isMissingNode()
                        && legacyEnv.path("loop").isMissingNode(),
                "无编排行=旧信封形状零回归（键不出）");

        Long collectionId = jdbcTemplate.queryForObject(
                "SELECT collection_id FROM tp_apitest_request WHERE id = ?",
                Long.class, legacy.id());
        ApiRequestInfo gated = apiRequestService.create(1L, new RequestSaveInput(
                collectionId, "轮询等状态", "GET", "/api/v1/orders/{id}",
                "[]", "", "{}", "[]",
                "{\"field\":\"var\",\"name\":\"token\",\"op\":\"exists\"}",
                "{\"mode\":\"count\",\"times\":5}"));
        apiRunService.create(1L, new RunCreateInput(gated.id(), envId));
        actionJson = jdbcTemplate.queryForObject(
                "SELECT action_json FROM tp_exec_task WHERE task_type = 4 AND project_id = ? "
                        + "ORDER BY id DESC LIMIT 1",
                String.class, pid);
        JsonNode env = MAPPER.readTree(actionJson);
        assertEquals("var", env.path("precondition").path("field").asText(),
                "前置条件经信封 precondition 键下发（执行器诚实跳过判定消费）");
        assertEquals("exists", env.path("precondition").path("op").asText());
        assertEquals(5, env.path("loop").path("times").asInt(),
                "循环配置经信封 loop 键下发");
    }
}
