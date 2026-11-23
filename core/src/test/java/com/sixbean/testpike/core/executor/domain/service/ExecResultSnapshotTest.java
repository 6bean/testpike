package com.sixbean.testpike.core.executor.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.api.dto.ExecTaskCreateInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.project.api.dto.ProjectCreateInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.domain.service.ProjectEnvService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.retention.domain.service.RetentionService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ExecResultSnapshotTest {

    private static final String ACTION_JSON =
            "{\"version\":\"v2\",\"steps\":[{\"action\":\"goto\",\"target\":{\"url\":\"http://demo.local/login\"}}]}";

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private CaseService caseService;

    @Autowired
    private ProjectEnvService projectEnvService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private RetentionService retentionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    // Fixture-scoped teardown : the name-family sweep walks the
    // FK graph from this suite's fixed-name project (tp_exec_step_snapshot
    // hangs under tp_exec_task, so the FK walk covers it; tp_exec_task is in
    // the helper's default project-keyed set). The executor rows drop by
    // their registered name family. A blanket TRUNCATE would wipe concurrent
    // suites' rows on the dev database.
    private static final java.util.List<String> FIXTURE_PROJECT_NAMES =
            java.util.List.of("快照项目");

    @BeforeEach
    @AfterEach
    void cleanTables() {
        com.sixbean.testpike.core.support.SharedDbFixtureSweep.byProjectNameFamily(
                jdbcTemplate, java.util.List.of(), FIXTURE_PROJECT_NAMES);
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-snap-%'");
    }

    private long createTask() {
        long pid = projectService.create(1L, new ProjectCreateInput("快照项目",
                "SNAP" + System.nanoTime() % 100000, "zh-CN", "P1", null));
        long envId = projectEnvService.create(pid, new ProjectEnvInput(
                "测试环境", 2, "http://demo.local", null, "web", "chromium", "v1.0.0"));
        CaseInfo info = caseService.create(1L, new CaseCreateInput(pid, "快照用例",
                "前置", "预期", 1, 2, "web", 3, null, 1, null, List.of()));
        return execTaskService.create(new ExecTaskCreateInput(pid, info.id(), "v2", ACTION_JSON, envId));
    }

    private ClaimTaskView claim(long taskId, String name) {
        long exId = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
        ClaimTaskView view = execTaskService.claim(exId);
        assertNotNull(view, "必须领取到任务: " + taskId);
        assertEquals(taskId, view.taskId());
        return view;
    }

    private void submit(long taskId, ClaimTaskView view, Object snapshots) {
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[{\"stepNo\":1,\"action\":\"http.request\",\"status\":\"ok\"}]",
                null, 1200, null, null, null, null, snapshots));
    }

    private Map<String, Object> snapshotRow(long taskId, int stepNo) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT step_no, truncated, raw_bytes, request_snapshot, response_snapshot,"
                        + " assertions_snapshot, tls_mode FROM tp_exec_step_snapshot"
                        + " WHERE task_id = ? AND step_no = ?", taskId, stepNo);
        assertEquals(1, rows.size(), "step " + stepNo + " 快照行必须存在且仅 1 行");
        return rows.get(0);
    }

    
    private String gunzip(Object blob) throws Exception {
        assertNotNull(blob, "gzip 快照列非空");
        try (GZIPInputStream in = new GZIPInputStream(
                new ByteArrayInputStream((byte[]) blob));
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }


    @Test
    void 读取链路_step快照按步序返回明文且UI步响应列为空() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-read");
        List<Map<String, Object>> snapshots = List.of(
                Map.of("stepNo", 2, "kind", "ui.click",
                        "request", Map.of("selector", "#btn-submit"),
                        "assertions", List.of(Map.of("kind", "expect.visible",
                                "expected", true, "actual", true))),
                Map.of("stepNo", 1, "kind", "http.request",
                        "request", Map.of("method", "GET", "url", "http://demo.local/a"),
                        "response", Map.of("status", 200, "body", "hi"),
                        "assertions", List.of()));
        submit(taskId, view, snapshots);

        List<ExecStepSnapshotView> rows = execTaskService.listStepSnapshots(taskId);
        assertEquals(2, rows.size(), "两步快照全返回");
        assertEquals(1, rows.get(0).stepNo(), "按 stepNo 升序（页签逐步展开口径）");
        assertEquals(2, rows.get(1).stepNo());
        assertEquals("http.request", rows.get(0).kind());
        assertEquals(taskId, rows.get(0).taskId());
        assertTrue(rows.get(0).request().contains("demo.local/a"), "请求 gunzip 明文回读");
        assertTrue(rows.get(0).response().contains("\"status\":200"), "响应 gunzip 明文回读");
        assertFalse(rows.get(0).truncated(), "预算内步=截断标记假");
        assertTrue(rows.get(0).rawBytes() > 0, "原体积透传（页签提示口径）");
        assertNull(rows.get(1).response(), "UI 步无 HTTP 响应=空（TC-V1-02）");
        assertTrue(rows.get(1).request().contains("#btn-submit"), "UI 步选择器留存");
        assertTrue(rows.get(1).assertions().contains("expect.visible"), "断言操作数留存");

        assertThrows(ServiceException.class, () -> execTaskService.listStepSnapshots(987654321L));
    }


    @Test
    void http步骤快照落库_请求响应断言三全且gzip可反解析() throws Exception {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-basic");
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1,
                "kind", "http.request",
                "request", Map.of("method", "POST", "url", "http://demo.local/api/order",
                        "headers", Map.of("Content-Type", "application/json"),
                        "body", Map.of("orderId", 42)),
                "response", Map.of("status", 200,
                        "headers", Map.of("X-Trace", "t-1"),
                        "body", "{\"ok\":true,\"id\":42}"),
                "assertions", List.of(Map.of("kind", "expect.status",
                        "expected", 200, "actual", 200))));

        submit(taskId, view, snapshots);

        Map<String, Object> row = snapshotRow(taskId, 1);
        assertEquals(0, ((Number) row.get("truncated")).intValue(), "预算内步=截断标记假");
        assertTrue(((Number) row.get("raw_bytes")).intValue() > 0, "原文体积记录");
        String request = gunzip(row.get("request_snapshot"));
        String response = gunzip(row.get("response_snapshot"));
        String assertions = gunzip(row.get("assertions_snapshot"));
        assertTrue(request.contains("http://demo.local/api/order"), "请求留存: " + request);
        assertTrue(request.contains("orderId"), "请求体留存: " + request);
        assertTrue(response.contains("\"ok\":true"), "响应留存: " + response);
        assertTrue(objectMapper.readTree(response).has("status"), "响应 status 在位");
        assertTrue(assertions.contains("expect.status"), "断言操作数留存: " + assertions);
        assertTrue(assertions.contains("expected") && assertions.contains("actual"),
                "断言期望/实际双留存（失败取证核心）");
    }


    @Test
    void 快照脱敏_敏感头与嵌套体键全掩码且掩码后合法可解析() throws Exception {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-mask");
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1,
                "kind", "http.request",
                "request", Map.of("method", "POST", "url", "http://demo.local/login",
                        "headers", Map.of("Authorization", "Bearer abc123def456",
                                "cookie", "session=xyz789",
                                "X-Normal", "keep-me"),
                        "body", Map.of("user", "zhangsan",
                                "nested", Map.of("Password", "P@ssw0rd123",
                                        "TOKEN", "tk-999888",
                                        "secret-note", "sn-777",
                                        "phone", "13812345678",
                                        "email", "zhangsan@example.com"))),
                "response", Map.of("status", 200, "body", "contact support@demo.com ok"),
                "assertions", List.of()));

        submit(taskId, view, snapshots);

        Map<String, Object> row = snapshotRow(taskId, 1);
        String request = gunzip(row.get("request_snapshot"));
        String response = gunzip(row.get("response_snapshot"));
        assertFalse(request.contains("abc123def456"), "Authorization 值必须掩码: " + request);
        assertFalse(request.contains("xyz789"), "Cookie 值必须掩码: " + request);
        assertFalse(request.contains("P@ssw0rd123"), "嵌套 password 变体必须掩码: " + request);
        assertFalse(request.contains("tk-999888"), "嵌套 TOKEN 大写变体必须掩码: " + request);
        assertFalse(request.contains("sn-777"), "嵌套 secret 前缀键必须掩码: " + request);
        var tree = objectMapper.readTree(request);
        assertEquals("Bearer ***MASKED***",
                tree.path("headers").path("Authorization").asText(), "敏感头=掩码占位");
        assertEquals("keep-me", tree.path("headers").path("X-Normal").asText(), "非敏感头原样");
        assertEquals("zhangsan", tree.path("body").path("user").asText(), "非敏感业务值原样留存");
        assertFalse(request.contains("13812345678"), "E5 手机号脱敏: " + request);
        assertFalse(request.contains("zhangsan@example.com"), "E5 邮箱脱敏: " + request);
        assertTrue(request.contains("138****5678"), "手机号打码形态: " + request);
        assertFalse(response.contains("support@demo.com"), "响应体邮箱 E5 脱敏: " + response);
    }


    @Test
    void 超预算步骤_截断标记真且内容在预算内() throws Exception {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-trunc");
        long cfgId = configService.list("系统").stream()
                .filter(c -> "exec.snapshot.max-bytes".equals(c.configKey()))
                .findFirst().orElseThrow().id();
        String original = configService.getValue("exec.snapshot.max-bytes", "262144");
        try {
            configService.update(1L, cfgId, new ConfigInput(
                    "exec.snapshot.max-bytes", "1024", "系统", "int", 0, "测试期临时配置值"));
            String bigBody = "x".repeat(6000);
            List<Map<String, Object>> snapshots = List.of(Map.of(
                    "stepNo", 1,
                    "kind", "http.request",
                    "request", Map.of("method", "GET", "url", "http://demo.local/big"),
                    "response", Map.of("status", 200, "body", bigBody),
                    "assertions", List.of()));

            submit(taskId, view, snapshots);

            Map<String, Object> row = snapshotRow(taskId, 1);
            assertEquals(1, ((Number) row.get("truncated")).intValue(),
                    "超预算步=截断标记真（TC-V1-01）");
            assertTrue(((Number) row.get("raw_bytes")).intValue() > 1024,
                    "原文体积>预算=记录原体积非截断后体积");
            byte[] stored = (byte[]) row.get("response_snapshot");
            assertTrue(stored.length < bigBody.length() / 4,
                    "落盘 gzip 体积远小于原文（截断真实发生）: stored=" + stored.length);
            String response = gunzip(row.get("response_snapshot"));
            assertTrue(response.contains("[truncated"), "截断标记人话可见: " + response);
            objectMapper.readTree(gunzip(row.get("request_snapshot")));
        } finally {
            configService.update(1L, cfgId, new ConfigInput(
                    "exec.snapshot.max-bytes", original, "系统", "int", 0, "用例恢复"));
        }
    }


    @Test
    void 非法快照schema_整包拒收且任务不被半写() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-badschema");

        assertThrows(ServiceException.class, () -> submit(taskId, view, "oops-not-array"));
        assertThrows(ServiceException.class, () -> submit(taskId, view,
                List.of(Map.of("kind", "http.request"))));
        assertThrows(ServiceException.class, () -> submit(taskId, view,
                List.of(Map.of("stepNo", "one"))));

        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(2, status, "整包拒收=任务保持执行中(2)，禁半写终态");
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_step_snapshot WHERE task_id = ?",
                Integer.class, taskId);
        assertEquals(0, cnt, "非法包零落库");
    }


    @Test
    void ui步骤快照_选择器与断言留存DOM不进协议表() throws Exception {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-ui");
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1,
                "kind", "ui",
                "request", Map.of("action", "click", "selector", "#username", "value", "tester"),
                "domSnapshot", "<html>巨大 DOM 快照禁进协议表防体积爆炸</html>",
                "assertions", List.of(Map.of("kind", "expect.text",
                        "selector", "#welcome", "expected", "欢迎", "actual", "欢迎"))));

        submit(taskId, view, snapshots);

        Map<String, Object> row = snapshotRow(taskId, 1);
        String request = gunzip(row.get("request_snapshot"));
        assertTrue(request.contains("#username"), "选择器留存: " + request);
        assertTrue(request.contains("tester"), "输入操作数留存");
        assertFalse(request.contains("巨大 DOM 快照"), "DOM 快照不进协议表（TC-V1-02）");
        assertNull(row.get("response_snapshot"), "UI 步无 HTTP 响应=列 NULL");
        String assertions = gunzip(row.get("assertions_snapshot"));
        assertTrue(assertions.contains("expect.text"), "断言操作数留存");
    }


    @Test
    void 事件帧tlsMode落库读取链路回读_缺键列NULL诚实占位() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-tls");
        List<Map<String, Object>> snapshots = List.of(
                Map.of("stepNo", 1, "kind", "http.request",
                        "request", Map.of("method", "GET", "url", "https://demo.local/a"),
                        "response", Map.of("status", 200, "body", "ok"),
                        "assertions", List.of(),
                        "tlsMode", "insecure"),
                Map.of("stepNo", 2, "kind", "http.request",
                        "request", Map.of("method", "GET", "url", "https://demo.local/b"),
                        "response", Map.of("status", 200, "body", "ok"),
                        "assertions", List.of()));
        submit(taskId, view, snapshots);

        assertEquals("insecure", snapshotRow(taskId, 1).get("tls_mode"),
                "tlsMode 原样落列（报告黄标数据源，insecure 不静默）");
        assertNull(snapshotRow(taskId, 2).get("tls_mode"),
                "缺键=列 NULL 诚实占位（旧执行器未采集≠strict，禁冒充）");

        List<ExecStepSnapshotView> rows = execTaskService.listStepSnapshots(taskId);
        assertEquals("insecure", rows.get(0).tlsMode(), "读路径尾追透传（V1 页签/报告消费面）");
        assertNull(rows.get(1).tlsMode(), "读路径 NULL 安全");
    }


    @Test
    void 保留策略_过期终态任务行删除快照级联清() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-retention");
        List<Map<String, Object>> snapshots = List.of(Map.of(
                "stepNo", 1, "kind", "http.request",
                "request", Map.of("method", "GET", "url", "http://demo.local/x"),
                "response", Map.of("status", 200, "body", "ok"),
                "assertions", List.of()));
        submit(taskId, view, snapshots);
        Integer before = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_step_snapshot WHERE task_id = ?",
                Integer.class, taskId);
        assertEquals(1, before, "前置：快照行在位");

        jdbcTemplate.update("UPDATE tp_exec_task SET created_at = now() - interval '400 days' WHERE id = ?",
                taskId);
        retentionService.run();

        Integer taskRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(0, taskRows, "过期终态任务行已删（保留策略既有语义零变化）");
        Integer after = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_step_snapshot WHERE task_id = ?",
                Integer.class, taskId);
        assertEquals(0, after, "快照随任务级联清理（16 §33 T229：随保留策略）");
    }


    @Test
    void 旧执行器不回传快照键_零快照行零破坏() {
        long taskId = createTask();
        ClaimTaskView view = claim(taskId, "ex-snap-legacy");
        execTaskService.submitResult(new ExecResultInput(
                taskId, view.claimToken(), "pass", null, null,
                "[{\"stepNo\":1,\"action\":\"goto\",\"status\":\"ok\"}]",
                null, 500, null));
        Integer cnt = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_step_snapshot WHERE task_id = ?",
                Integer.class, taskId);
        assertEquals(0, cnt, "无快照回传=零落库（尾追兼容先例 T192）");
        Integer status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_exec_task WHERE id = ?", Integer.class, taskId);
        assertEquals(3, status, "result 主候选端点语义不受快照扩展影响");
    }
}
