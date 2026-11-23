package com.sixbean.testpike.core.tool.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenKit;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.tool.api.dto.ToolConfirmTicket;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.core.executor.api.dto.ClaimTaskView;
import com.sixbean.testpike.core.executor.api.dto.ExecResultInput;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import com.sixbean.testpike.core.executor.domain.service.ExecutorService;
import com.sixbean.testpike.core.mcp.api.dto.McpServerCreateInput;
import com.sixbean.testpike.core.mcp.domain.component.MockMcpServerFixture;
import com.sixbean.testpike.core.mcp.domain.service.McpServerService;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.support.ToolSuiteSupport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class ToolExecutionServiceTest {

    private static final String INPUT_JSON = "{\"q\":\"hello\"}";
    
    private static final String AUTH_REF_KEY = "tool.auth-" + "ref." + "t87";

    @Autowired
    private ToolExecutionService toolExecutionService;

    @Autowired
    private ToolService toolService;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ExecTaskService execTaskService;

    @Autowired
    private ExecutorService executorService;

    private HttpServer server;
    
    private volatile String lastAuthHeader;
    private volatile String lastBody;

    
    private ToolSuiteSupport support;
    
    private final List<Long> executorIds = new ArrayList<>();
    
    private final List<Long> mcpServerIds = new ArrayList<>();
    
    private long execTaskFloor;

    
    private static final String[] FIXTURE_EXECUTOR_NAMES = {
        "ex-tool-run", "ex-tool-fail", "ex-tool-hang",
    };

    /**
 * Fixed tool names this suite creates through the direct service path
 * (no run-token). A crashed round leaves them on the shared dev
 * database, and the name-uniqueness rule then rejects the re-created
 * already pre-clears. Every entry here is suite-owned (the factory
 * baseline carries none of these names), so equality deletion before
 * the suite opens cannot touch shipped rows.
 */
    private static final String[] FIXTURE_TOOL_NAMES = {
        "取数HTTP", "慢工具", "HTML目标", "大响应", "炸目标", "失联目标", "带鉴权",
        "系统级OCR岗", "系统级走私岗", "无锚载具岗", "AI取数", "将下架", "只读带token",
        "规范文档", "桥工具", "越权执行", "草稿工具", "AI规范文档", "写门草稿",
        "沙箱取数", "无人值守", "炸脚本", "挂死脚本", "执行类复校",
        "身份防伪项目", "身份防伪操作者", "身份可信值", "项目行岗",
        "写门签发", "写门裸调", "写门放行", "写门伪造",
        "MD5摘要", "身份证生成", "坏函数", "缺函数", "编码工具",
    };

    @BeforeEach
    void openFixture() {
        support = ToolSuiteSupport.beginSuite(jdbcTemplate, List.of());
        execTaskFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_exec_task");
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_executor",
                jdbcTemplate.queryForList("SELECT id FROM tp_executor WHERE name IN ("
                        + quotedNames(FIXTURE_EXECUTOR_NAMES) + ")", Long.class));
        // Same crash-residue guard for the tool lane: stale fixed-name rows
        // make the name-uniqueness rule reject the re-created fixture
        // deleteTools takes the invocation/notification/child walk with it.
        SharedDbFixtureSweep.deleteTools(jdbcTemplate,
                jdbcTemplate.queryForList("SELECT id FROM tp_tool WHERE name IN ("
                        + quotedNames(FIXTURE_TOOL_NAMES) + ")", Long.class));
    }

    @AfterEach
    void sweepFixtureRows() {
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_exec_task", execTaskFloor);
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_executor", executorIds);
        executorIds.clear();
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server", mcpServerIds);
        mcpServerIds.clear();
        support.sweepNewTools();
        support.sweep();
        // stage 1: test-band membership rows (user_id 900000+) and the
        // auth-ref config keys keep their existing targeted deletes.
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE user_id >= 900000");
        jdbcTemplate.update("DELETE FROM tp_sys_config WHERE config_key LIKE 'tool.auth-ref.%'");
        configCacheHolder.remove(AUTH_REF_KEY);
    }

    private static String quotedNames(String... names) {
        StringBuilder sb = new StringBuilder();
        for (String n : names) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append('\'').append(n).append('\'');
        }
        return sb.toString();
    }

    @BeforeEach
    void startFakeTarget() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopFakeTarget() {
        if (server != null) {
            server.stop(0);
        }
    }

    // ---- helpers ----

    private String targetUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    
    private void respond(String path, int status, String contentType, byte[] body, long sleepMs) {
        server.createContext(path, exchange -> {
            try {
                if (sleepMs > 0) {
                    Thread.sleep(sleepMs);
                }
                lastAuthHeader = exchange.getRequestHeaders().getFirst("Authorization");
                lastBody = readAll(exchange.getRequestBody());
                if (body != null && body.length > 0) {
                    exchange.getResponseHeaders().add("Content-Type", contentType);
                    exchange.sendResponseHeaders(status, body.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(body);
                    }
                } else {
                    exchange.sendResponseHeaders(status, -1);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String readAll(InputStream in) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            in.transferTo(buf);
            return buf.toString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    
    private ToolInfo publishedHttpTool(String name, String execSpecJson, Integer timeoutMs) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(null, name, 2, "取数",
                null, null, null, null, null, 1, execSpecJson, timeoutMs, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    private ToolTestRunResult run(ToolInfo tool, String input) {
        return toolExecutionService.testRun(1L, tool.id(), input);
    }

    
    private record TraceRow(String caller, Long actorId, Long toolId, String toolName,
                            int success, Long durationMs, String error, String digestHash,
                            Timestamp createdAt, Long serverId, Long taskId, Integer masked) {
    }

    private TraceRow onlyTrace(long toolId) {
        return jdbcTemplate.query("SELECT caller, actor_id, tool_id, tool_name, success, "
                        + "duration_ms, error, digest_hash, created_at, server_id, task_id, masked "
                        + "FROM tp_tool_invocation WHERE tool_id = ?",
                (rs, i) -> new TraceRow(rs.getString("caller"), rs.getObject("actor_id", Long.class),
                        rs.getLong("tool_id"), rs.getString("tool_name"), rs.getInt("success"),
                        rs.getObject("duration_ms", Long.class), rs.getString("error"),
                        rs.getString("digest_hash"), rs.getTimestamp("created_at"),
                        rs.getObject("server_id", Long.class),
                        rs.getObject("task_id", Long.class),
                        rs.getObject("masked", Integer.class)),
                toolId)
                .stream().findFirst().orElseThrow(() -> new AssertionError("留痕缺失（tool_id=" + toolId + "）"));
    }

    private long auditCount(long toolId, LocalDateTime since) {
        return auditCount(toolId, "test-run", since);
    }

    
    private long auditCount(long toolId, String action, LocalDateTime since) {
        Long n = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_audit_log "
                + "WHERE object_type = 'tool' AND action = ? "
                + "AND object_id = ? AND created_at >= ?", Long.class,
                action,
                String.valueOf(toolId),
                Timestamp.valueOf(since));
        return n == null ? 0 : n;
    }


    @Test
    void HTTP试跑成功_留痕全列caller_user_审计恰1() {
        respond("/ok", 200, "application/json", utf8("{\"answer\":42}"), 0);
        ToolInfo tool = publishedHttpTool("取数HTTP",
                "{\"url\":\"" + targetUrl("/ok") + "\"}", 5000);

        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(tool.id(), result.toolId());
        assertEquals("取数HTTP", result.toolName());
        assertTrue(result.output().contains("\"answer\":42"), result.output());
        assertNull(result.errorCategory());
        assertNotNull(result.durationMs());

        TraceRow trace = onlyTrace(tool.id());
        assertEquals("user", trace.caller());
        assertEquals(1L, trace.actorId());
        assertEquals("取数HTTP", trace.toolName());
        assertEquals(1, trace.success());
        assertNotNull(trace.durationMs());
        assertNull(trace.error());
        assertEquals(DigestHex.sha256(INPUT_JSON), trace.digestHash());
        assertNotNull(trace.createdAt());
        assertEquals(INPUT_JSON, lastBody);
        assertEquals(1, auditCount(tool.id(), since));
    }


    @Test
    void HTTP目标挂死_超时中断50401系预算人话_留痕失败() {
        respond("/hang", 200, "application/json", utf8("{}"), 3000);
        ToolInfo tool = publishedHttpTool("慢工具", "{\"url\":\"" + targetUrl("/hang") + "\"}", 300);

        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertFalse(result.success());
        assertEquals("timeout", result.errorCategory());
        assertEquals(BizErrorCode.AI_TIMEOUT, result.errorCode());
        assertTrue(result.errorMessage().contains("超时"), result.errorMessage());
        assertTrue(result.errorMessage().contains("300"), result.errorMessage());

        TraceRow trace = onlyTrace(tool.id());
        assertEquals(0, trace.success());
        assertTrue(trace.error().contains("超时"), trace.error());
        assertEquals(1, auditCount(tool.id(), since));
    }


    @Test
    void HTTP返回非JSON_原样截断留痕人话() {
        respond("/html", 200, "text/html", utf8("<html>网关报错页</html>"), 0);
        ToolInfo tool = publishedHttpTool("HTML目标", "{\"url\":\"" + targetUrl("/html") + "\"}", 5000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertFalse(result.success());
        assertEquals("format", result.errorCategory());
        assertTrue(result.errorMessage().contains("不是 JSON"), result.errorMessage());
        TraceRow trace = onlyTrace(tool.id());
        assertEquals(0, trace.success());
        assertTrue(trace.error().contains("网关报错页"), trace.error());
    }


    @Test
    void HTTP响应超1MB_截断并标注体积上限() {
        String big = "x".repeat(2 * 1024 * 1024);
        respond("/big", 200, "application/json", utf8("{\"d\":\"" + big + "\"}"), 0);
        ToolInfo tool = publishedHttpTool("大响应", "{\"url\":\"" + targetUrl("/big") + "\"}", 10000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().length() <= 1024 * 1024 + 64,
                "未截断: " + result.output().length());
        assertTrue(result.output().endsWith("…[响应超 1MB，已截断]"), result.output());
    }


    @Test
    void HTTP目标500_结果失败http分类_异常不外抛() {
        respond("/err", 500, "application/json", utf8("{\"e\":\"boom\"}"), 0);
        ToolInfo tool = publishedHttpTool("炸目标", "{\"url\":\"" + targetUrl("/err") + "\"}", 5000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertFalse(result.success());
        assertEquals("http", result.errorCategory());
        assertTrue(result.errorMessage().contains("HTTP 500"), result.errorMessage());
        assertEquals(0, onlyTrace(tool.id()).success());
    }


    @Test
    void HTTP连接拒绝_network分类人话() {
        server.stop(0);
        int deadPort = server.getAddress().getPort();
        server = null;
        ToolInfo tool = publishedHttpTool("失联目标",
                "{\"url\":\"http://127.0.0.1:" + deadPort + "/x\"}", 5000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertFalse(result.success());
        assertEquals("network", result.errorCategory());
        assertTrue(result.errorMessage().contains("无法连接"), result.errorMessage());
    }


    @Test
    void authRef经K8配置解析注入Authorization头() {
        respond("/auth", 200, "application/json", utf8("{\"ok\":true}"), 0);
        configCacheHolder.put(AUTH_REF_KEY, "t0k3n-sec");
        ToolInfo tool = publishedHttpTool("带鉴权",
                "{\"url\":\"" + targetUrl("/auth") + "\",\"authRef\":\"t87\"}", 5000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertTrue(result.success(), result.errorMessage());
        assertEquals("Bearer t0k3n-sec", lastAuthHeader);
    }


    @Test
    void DOC工具试跑40001人话_工具不存在40401() {
        ToolInfo doc = toolService.create(1L, new ToolCreateInput(null, "规范文档", 1, "规范",
                null, "正文", null, null, null, null, null, null, null));
        ServiceException docErr = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, doc.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, docErr.getCode());
        assertTrue(docErr.getMessage().contains("文档类"), docErr.getMessage());

        ServiceException nf = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, 999_999L, INPUT_JSON));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());

        assertEquals(0L, runTraceCount());
    }


    
    private ToolInfo publishedExecutorTool(String name, String script, Integer timeoutMs) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(null, name, 2, "沙箱",
                null, script, null, null, null, 2, "{\"scriptEntry\":\"main.py\"}", timeoutMs, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    private long registerLiveExecutor(String name) {
        long id = executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "v1", 0));
        executorIds.add(id);
        return id;
    }

    
    private Thread simulatedExecutor(long exId, String result, String log, String errorDetail,
                                     AtomicReference<ClaimTaskView> claimedRef) {
        Thread t = new Thread(() -> {
            ClaimTaskView view = null;
            for (int i = 0; i < 300 && view == null; i++) {
                try {
                    view = execTaskService.claim(exId);
                } catch (RuntimeException e) {
                }
                if (view == null) {
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            if (view == null) {
                return;
            }
            claimedRef.set(view);
            if (result == null) {
                return;
            }
            execTaskService.submitResult(new ExecResultInput(view.taskId(), view.claimToken(),
                    result, "fail".equals(result) ? 1 : null, errorDetail, null, null, 120, log));
        });
        t.setDaemon(true);
        return t;
    }

    
    private void assertToolClaimEnvelope(AtomicReference<ClaimTaskView> ref, String scriptMarker) {
        ClaimTaskView view = ref.get();
        assertNotNull(view, "执行器必须能经 claim 通道领取到工具任务（红线6 零新通道）");
        assertEquals(2, view.taskType(), "工具任务必须下发 taskType=2");
        assertTrue(view.actionJson().contains(scriptMarker), "actionJson 必须携带脚本信封");
    }

    
    private long runTraceCount() {
        Set<Long> ids = support.newToolIds();
        if (ids.isEmpty()) {
            return 0;
        }
        String in = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        Long n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE tool_id IN (" + in + ")",
                Long.class);
        return n == null ? 0 : n;
    }

    
    private long runToolActionAuditCount() {
        List<Long> ids = new ArrayList<>(support.newToolIds());
        return support.toolAuditCount("test-run", ids) + support.toolAuditCount("invoke", ids);
    }

    @Test
    void EXECUTOR领取成功_输出回显留痕审计恰1() {
        ToolInfo tool = publishedExecutorTool("沙箱取数", "print('{\"answer\":42}')", 8000);
        long exId = registerLiveExecutor("ex-tool-run");
        AtomicReference<ClaimTaskView> claimed = new AtomicReference<>();
        simulatedExecutor(exId, "pass", "{\"answer\":42}", null, claimed).start();

        ToolTestRunResult result = toolExecutionService.testRun(1L, tool.id(), INPUT_JSON);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains("\"answer\":42"), result.output());
        assertToolClaimEnvelope(claimed, "print");
        TraceRow trace = onlyTrace(tool.id());
        assertEquals(1, trace.success());
        assertEquals(DigestHex.sha256(INPUT_JSON), trace.digestHash());
        assertEquals(1, auditCount(tool.id(), LocalDateTime.now().minusSeconds(30)));
    }

    @Test
    void EXECUTOR无在线执行器_排队人话_任务滞留待派发() {
        ToolInfo tool = publishedExecutorTool("无人值守", "print(1)", 2000);

        ToolTestRunResult result = toolExecutionService.testRun(1L, tool.id(), INPUT_JSON);

        assertFalse(result.success());
        assertTrue(result.errorMessage().contains("无可用执行器"), result.errorMessage());
        assertEquals(0, onlyTrace(tool.id()).success());
        Long queued = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE task_type = 2 AND status = 1"
                        + " AND id > " + execTaskFloor, Long.class);
        assertEquals(1L, queued);
    }

    @Test
    void EXECUTOR非零退出_失败分类executor_错误详情透传() {
        ToolInfo tool = publishedExecutorTool("炸脚本", "raise SystemExit(1)", 8000);
        long exId = registerLiveExecutor("ex-tool-fail");
        AtomicReference<ClaimTaskView> claimed = new AtomicReference<>();
        simulatedExecutor(exId, "fail", null, "exit code 1: Traceback: ValueError 入参非法",
                claimed).start();

        ToolTestRunResult result = toolExecutionService.testRun(1L, tool.id(), INPUT_JSON);

        assertFalse(result.success());
        assertEquals("executor", result.errorCategory());
        assertTrue(result.errorMessage().contains("Traceback"), result.errorMessage());
        assertToolClaimEnvelope(claimed, "raise");
        assertEquals(0, onlyTrace(tool.id()).success());
    }

    @Test
    void EXECUTOR领取未回传_预算耗尽超时kill分型() {
        ToolInfo tool = publishedExecutorTool("挂死脚本", "while True: pass", 1200);
        long exId = registerLiveExecutor("ex-tool-hang");
        AtomicReference<ClaimTaskView> claimed = new AtomicReference<>();
        simulatedExecutor(exId, null, null, null, claimed).start();

        ToolTestRunResult result = toolExecutionService.testRun(1L, tool.id(), INPUT_JSON);

        assertFalse(result.success());
        assertEquals("timeout", result.errorCategory());
        assertEquals(BizErrorCode.AI_TIMEOUT, result.errorCode());
        assertTrue(result.errorMessage().contains("1200"), result.errorMessage());
        assertTrue(result.errorMessage().contains("超时"), result.errorMessage());
        assertToolClaimEnvelope(claimed, "while");
        assertEquals(0, onlyTrace(tool.id()).success());
    }

    @Test
    void MCP_BRIDGE引用服务不存在_人话拒零留痕() {
        ToolInfo bridge = toolService.create(1L, new ToolCreateInput(null, "桥工具", 2, null,
                null, null, null, null, null, 3, "{\"serverId\":1,\"toolName\":\"echo\"}",
                null, null));
        ToolInfo submitted = toolService.submit(1L, bridge.id(), bridge.version());
        ToolInfo published = toolService.approve(2L, submitted.id(), submitted.version());

        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, published.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("MCP 服务 1 不存在或已停用"), err.getMessage());

        assertEquals(0L, runTraceCount());
    }

    // ---- ⑬ stage 1: invoke execution-point re-check (five-way gate) ----

    
    private long createProjectRow(String code) {
        return support.createProject(code);
    }

    /** Published EXECUTOR tool inside a given project segment. */
    private ToolInfo publishedExecutorToolIn(long projectId, String name) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(projectId, name, 2, "沙箱",
                null, "print(1)", null, null, null, 2, "{\"scriptEntry\":\"main.py\"}", 2000, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    @Test
    void invoke_非成员执行他人项目工具_40301零留痕零审计() {
        long pid = createProjectRow("EX01");
        respond("/gate-no", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo created = toolService.create(1L, new ToolCreateInput(pid, "越权执行", 2, "取数",
                null, null, null, null, null, 1, "{\"url\":\"" + targetUrl("/gate-no") + "\"}",
                3000, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        ToolInfo tool = toolService.approve(2L, submitted.id(), submitted.version());
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        long stranger = 900011L;   // no membership row, not an admin

        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(stranger, 9L, tool.id(), INPUT_JSON));
        assertEquals(BizErrorCode.FORBIDDEN, err.getCode(),
                "水平越权执行点复校 40301（裁决件 §四：每次 tool_call 执行点复校）");
        assertEquals(0L, runTraceCount());
        assertEquals(0, auditCount(tool.id(), "invoke", since));
    }

    @Test
    void invoke_exec开关关_EXECUTOR硬拒零留痕_开关开成员放行到排队() {
        long pid = createProjectRow("EX02");
        long member = 900012L;
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) "
                + "VALUES (?, ?, 1)", pid, member);
        ToolInfo tool = publishedExecutorToolIn(pid, "执行类复校");
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);

        // The shipped default for the execution switch is TRUE (master AI
        // feature rule: on by default). Capture the live value before the
        // off-spectrum flip so the finally block restores the row to exactly
        // what the environment shipped, instead of hard-coding 'false' — the
        // old cleanup predated the default-on ruling and left the shared dev
        // DB switched off for every later consumer (the live goal-card E2E
        // window hit the honest refusal frame instead of a real run).
        String execSwitchBefore = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config "
                        + "WHERE config_key = 'ai.chat.exec-enabled'", String.class);

        // off spectrum: EXECUTOR invoke refused with literal key guidance, zero trace
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'false' "
                + "WHERE config_key = 'ai.chat.exec-enabled'");
        configCacheHolder.put("ai.chat.exec-enabled", "false");
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(member, 9L, tool.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("ai.chat.exec-enabled"),
                "关闭态指路必点名参数名: " + err.getMessage());
        assertEquals(0L, runTraceCount());
        assertEquals(0, auditCount(tool.id(), "invoke", since));

        try {
            // on spectrum: member passes visibility; EXECUTOR reaches the real
            // queue path (no live executor = honest queued failure, trace lands)
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = 'true' "
                    + "WHERE config_key = 'ai.chat.exec-enabled'");
            configCacheHolder.put("ai.chat.exec-enabled", "true");
            ToolTestRunResult result =
                    toolExecutionService.invoke(member, 9L, tool.id(), INPUT_JSON);
            assertFalse(result.success());
            assertTrue(result.errorMessage().contains("无可用执行器"), result.errorMessage());
            assertEquals("ai", onlyTrace(tool.id()).caller());
        } finally {
            // restore the shipped value captured at entry (never a hard-coded
            // 'false': the row must survive the run for every later consumer)
            jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? "
                    + "WHERE config_key = 'ai.chat.exec-enabled'", execSwitchBefore);
            configCacheHolder.put("ai.chat.exec-enabled", execSwitchBefore);
        }
    }

    // ---- ⑭ stage 3: trusted-identity seam on the AI invoke chain ----

    /** Published HTTP tool inside a fresh project segment, invoked by a member. */
    private ToolInfo idTool(long projectId, String name, String path) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(projectId, name, 2, "取数",
                null, null, null, null, null, 1,
                "{\"url\":\"" + targetUrl(path) + "\"}", 3000, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    private void addMember(long projectId, long userId) {
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 1)",
                projectId, userId);
    }

    @Test
    void invoke_参数夹带伪造projectId_拒且失败留痕原文_不进执行面() {
        long pid = createProjectRow("ID01");
        long otherPid = createProjectRow("ID02");
        long member = 900021L;
        addMember(pid, member);
        respond("/id-ok", 200, "application/json", utf8("{\"answer\":1}"), 0);
        ToolInfo tool = idTool(pid, "身份防伪项目", "/id-ok");
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);

        String dirty = "{\"q\":\"x\",\"projectId\":" + otherPid + "}";
        ToolTestRunResult refusal = toolExecutionService.invoke(member, 9L, tool.id(), dirty);

        assertFalse(refusal.success(), "模型自带身份键必须被拒（裁决件 §四：即拒留痕）");
        assertEquals("identity", refusal.errorCategory());
        assertEquals(BizErrorCode.FORBIDDEN, refusal.errorCode());
        assertTrue(refusal.errorMessage().contains("projectId"), refusal.errorMessage());
        assertNull(lastBody, "拒绝先于执行面（假目标不得收到载荷）");

        TraceRow trace = onlyTrace(tool.id());
        assertEquals(0, trace.success());
        assertEquals("ai", trace.caller());
        assertEquals(DigestHex.sha256(dirty), trace.digestHash(), "留痕 digest=入参原文");
        assertTrue(trace.error() != null && trace.error().contains("服务端注入"), trace.error());
        assertEquals(1, auditCount(tool.id(), "invoke", since), "身份拒=留痕+审计恰1（与守卫零留痕谱刻意分型）");
    }

    @Test
    void invoke_参数夹带伪造操作者身份键_三类键均拒且逐条留痕() {
        long pid = createProjectRow("ID03");
        long member = 900022L;
        addMember(pid, member);
        respond("/id-ok2", 200, "application/json", utf8("{\"answer\":1}"), 0);
        ToolInfo tool = idTool(pid, "身份防伪操作者", "/id-ok2");

        for (String key : new String[]{"actorId", "operatorId", "userId"}) {
            String dirty = "{\"q\":\"x\",\"" + key + "\":999999}";
            ToolTestRunResult refusal = toolExecutionService.invoke(member, 9L, tool.id(), dirty);
            assertFalse(refusal.success(), key + " 冒名值必须拒");
            assertEquals("identity", refusal.errorCategory());
            assertTrue(refusal.errorMessage().contains(key), refusal.errorMessage());
        }
        assertNull(lastBody, "三次均拒绝于执行面之前");
        Long rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE tool_id = ?", Long.class, tool.id());
        assertEquals(3L, rows, "每条身份拒各留痕恰1");
    }

    @Test
    void invoke_身份键与账行一致_放行到执行面且留痕成功() {
        long pid = createProjectRow("ID04");
        long member = 900023L;
        addMember(pid, member);
        respond("/id-ok3", 200, "application/json", utf8("{\"answer\":3}"), 0);
        ToolInfo tool = idTool(pid, "身份可信值", "/id-ok3");

        String clean = "{\"q\":\"x\",\"projectId\":\"" + pid + "\",\"actorId\":" + member + "}";
        ToolTestRunResult result = toolExecutionService.invoke(member, 9L, tool.id(), clean);

        assertTrue(result.success(), result.errorMessage());
        // string-vs-number forms of the trusted value are identity-equivalent
        assertEquals(clean, lastBody, "放行谱=原样送达（现状不改写载荷）");
        assertEquals(1, onlyTrace(tool.id()).success());
    }

    // ---- ⑮ : carrier-carried project segment on system-level tools ----
    // Shape knowledge at the single consumption point (R51 precedent): the two
    // dialog OCR read doors are system-level rows (tp_tool.project_id NULL) that
    // the conversation anchor stamps with the session project. Without the
    // asserted carrier value the trusted fact degenerates to "null" and every
    // server-stamped call reads as forgery — the doors were physically unusable
    // in the live dialog chain (root-cause, tp_tool_invocation 5753-5756).

    @Test
    void invoke_系统级工具载具断言项目_与盖章切片一致_放行到执行面() {
        long sessionPid = createProjectRow("CR01");
        long member = 900031L;
        respond("/cr-ok", 200, "application/json", utf8("{\"lines\":1}"), 0);
        ToolInfo tool = publishedHttpTool("系统级OCR岗",
                "{\"url\":\"" + targetUrl("/cr-ok") + "\"}", 3000);

        String stamped = "{\"objectKey\":\"k\",\"projectId\":" + sessionPid
                + ",\"operatorId\":" + member + "}";
        ToolTestRunResult result = toolExecutionService.invoke(
                member, 9L, tool.id(), stamped, null, sessionPid);

        assertTrue(result.success(), result.errorMessage());
        assertEquals(stamped, lastBody, "放行谱=原样送达（不改写载荷）");
        assertEquals(1, onlyTrace(tool.id()).success());
    }

    @Test
    void invoke_系统级工具载荷项目与载具断言矛盾_身份拒且留痕() {
        long sessionPid = createProjectRow("CR02");
        long otherPid = createProjectRow("CR03");
        long member = 900032L;
        respond("/cr-x", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = publishedHttpTool("系统级走私岗",
                "{\"url\":\"" + targetUrl("/cr-x") + "\"}", 3000);

        ToolTestRunResult refusal = toolExecutionService.invoke(
                member, 9L, tool.id(), "{\"projectId\":" + otherPid + "}", null, sessionPid);

        assertFalse(refusal.success());
        assertEquals("identity", refusal.errorCategory());
        assertTrue(refusal.errorMessage().contains("projectId"), refusal.errorMessage());
        assertNull(lastBody, "拒绝先于执行面");
        assertEquals(0, onlyTrace(tool.id()).success());
    }

    @Test
    void invoke_系统级工具载具无锚_冒名键照旧拒_走私面零放宽() {
        long sessionPid = createProjectRow("CR04");
        respond("/cr-y", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = publishedHttpTool("无锚载具岗",
                "{\"url\":\"" + targetUrl("/cr-y") + "\"}", 3000);

        ToolTestRunResult numeric = toolExecutionService.invoke(
                9L, 9L, tool.id(), "{\"projectId\":" + sessionPid + "}", null, null);
        assertEquals("identity", numeric.errorCategory(), "载具无锚=照旧拒（异步生成链形态）");

        // The old implementation degenerated the absent trusted value to the
        // text "null", so a literal "null" claim rode through. Fail-closed now.
        ToolTestRunResult textual = toolExecutionService.invoke(
                9L, 9L, tool.id(), "{\"projectId\":\"null\"}", null, null);
        assertEquals("identity", textual.errorCategory(), "文本 null 冒名=拒");
        assertNull(lastBody);
    }

    @Test
    void invoke_项目级工具账行事实先于载具断言() {
        long toolPid = createProjectRow("CR05");
        long carrierPid = createProjectRow("CR06");
        long member = 900033L;
        addMember(toolPid, member);
        respond("/cr-z", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = idTool(toolPid, "项目行岗", "/cr-z");

        ToolTestRunResult pass = toolExecutionService.invoke(member, 9L, tool.id(),
                "{\"projectId\":" + toolPid + "}", null, carrierPid);
        assertTrue(pass.success(), pass.errorMessage());

        ToolTestRunResult refuse = toolExecutionService.invoke(member, 9L, tool.id(),
                "{\"projectId\":" + carrierPid + "}", null, carrierPid);
        assertEquals("identity", refuse.errorCategory(), "载具断言不得覆盖工具账行");
    }


    @Autowired
    private McpServerService mcpServerService;

    
    private long registerMcpServer(String name, String endpoint, String toolsJson) {
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_mcp_server",
                jdbcTemplate.queryForList("SELECT id FROM tp_mcp_server WHERE name = ?",
                        Long.class, name));
        var info = mcpServerService.create(1L, new McpServerCreateInput(
                name, endpoint, 2, "tok-" + name));
        mcpServerIds.add(info.id());
        jdbcTemplate.update("UPDATE tp_mcp_server SET tools_json = ?::jsonb, enabled = 1,"
                + " health_status = 1, last_probe_at = now() WHERE id = ?", toolsJson, info.id());
        return info.id();
    }

    private ToolInfo publishedBridgeTool(String name, long serverId, String remoteTool, int timeoutMs) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(null, name, 2, null,
                null, null, null, null, null, 3,
                "{\"serverId\":" + serverId + ",\"toolName\":\"" + remoteTool + "\"}",
                timeoutMs, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    @Test
    void MCP_BRIDGE转发成功_留痕含serverId_鉴权透传_审计恰1() throws Exception {
        try (MockMcpServerFixture fixture = MockMcpServerFixture.start("normal")) {
            long sid = registerMcpServer("bridge-ok", fixture.endpoint(),
                    "[{\"name\":\"kb_search\",\"description\":\"检索\",\"enabled\":true},"
                            + "{\"name\":\"shell_exec\",\"description\":\"危险\",\"enabled\":false}]");
            ToolInfo tool = publishedBridgeTool("桥接检索", sid, "kb_search", 5000);

            LocalDateTime since = LocalDateTime.now().minusSeconds(5);
            ToolTestRunResult result = run(tool, INPUT_JSON);

            assertTrue(result.success(), result.errorMessage());
            assertTrue(result.output().contains("mock result of kb_search"), result.output());
            TraceRow trace = onlyTrace(tool.id());
            assertEquals("user", trace.caller());
            assertEquals(sid, trace.serverId(), "MCP_BRIDGE 留痕必须带 serverId（G7 引用面）");
            assertEquals(1, trace.success());
            assertEquals(DigestHex.sha256(INPUT_JSON), trace.digestHash());
            assertEquals(1, auditCount(tool.id(), since));
            assertTrue(fixture.authLogContent().contains("Bearer tok-bridge-ok"),
                    fixture.authLogContent());
        }
    }

    /**
     * A tool-run of an MCP_BRIDGE tool IS an outbound call, so the trace row
     * must carry the honest desensitization facet. With the gate at its
     * fail-closed default (key absent = ON) a successful outbound body is
     * recorded masked=1 — the audit trail never pretends the facet does not
     * apply to this face.
     */
    @Test
    void MCP_BRIDGE试跑出站成功_留痕脱敏标记登记为1() throws Exception {
        try (MockMcpServerFixture fixture = MockMcpServerFixture.start("normal")) {
            long sid = registerMcpServer("bridge-masked", fixture.endpoint(),
                    "[{\"name\":\"kb_search\",\"enabled\":true}]");
            ToolInfo tool = publishedBridgeTool("桥接脱敏留痕", sid, "kb_search", 5000);

            ToolTestRunResult result = run(tool, INPUT_JSON);

            assertTrue(result.success(), result.errorMessage());
            assertEquals(Integer.valueOf(1), onlyTrace(tool.id()).masked(),
                    "gate on by default: the MCP_BRIDGE outbound body must record masked=1");
        }
    }

    @Test
    void MCP_BRIDGE勾选集外工具_人话拒零留痕() throws Exception {
        try (MockMcpServerFixture fixture = MockMcpServerFixture.start("normal")) {
            long sid = registerMcpServer("bridge-wl", fixture.endpoint(),
                    "[{\"name\":\"kb_search\",\"enabled\":true},{\"name\":\"shell_exec\",\"enabled\":false}]");
            ToolInfo tool = publishedBridgeTool("桥接越权", sid, "shell_exec", 5000);

            ServiceException err = assertThrows(ServiceException.class,
                    () -> run(tool, INPUT_JSON));
            assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
            assertTrue(err.getMessage().contains("白名单"), err.getMessage());
            assertTrue(err.getMessage().contains("shell_exec"), err.getMessage());
            assertEquals(0L, runTraceCount());
        }
    }

    @Test
    void MCP_BRIDGE端点不可达_network分类不抛5xx() {
        int deadPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            deadPort = s.getLocalPort();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        long sid = registerMcpServer("bridge-dead", "http://127.0.0.1:" + deadPort + "/mcp",
                "[{\"name\":\"kb_search\",\"enabled\":true}]");
        ToolInfo tool = publishedBridgeTool("桥接不可达", sid, "kb_search", 5000);

        ToolTestRunResult result = run(tool, INPUT_JSON);

        assertFalse(result.success());
        assertEquals("network", result.errorCategory());
        assertEquals(0, onlyTrace(tool.id()).success());
    }


    @Test
    void invoke_HTTP成功_留痕caller_ai_带taskId_审计invoke恰1试跑零() {
        respond("/ai-ok", 200, "application/json", utf8("{\"answer\":7}"), 0);
        ToolInfo tool = publishedHttpTool("AI取数", "{\"url\":\"" + targetUrl("/ai-ok") + "\"}", 5000);

        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        ToolTestRunResult result = toolExecutionService.invoke(7L, 4242L, tool.id(), INPUT_JSON);

        assertTrue(result.success(), result.errorMessage());
        assertTrue(result.output().contains("\"answer\":7"), result.output());
        TraceRow trace = onlyTrace(tool.id());
        assertEquals("ai", trace.caller());
        assertEquals(7L, trace.actorId());
        assertEquals(4242L, trace.taskId());
        assertEquals(1, trace.success());
        assertEquals(DigestHex.sha256(INPUT_JSON), trace.digestHash());
        assertEquals(1, auditCount(tool.id(), "invoke", since));
        assertEquals(0, auditCount(tool.id(), "test-run", since));
    }

    @Test
    void invoke_未发布与已下架工具_人话40001拒_零留痕零审计() {
        ToolInfo draft = toolService.create(1L, new ToolCreateInput(null, "草稿工具", 2, "数据",
                null, null, null, null, null, 1, "{\"url\":\"http://127.0.0.1:9/x\"}", 3000, null));
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(1L, 1L, draft.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("未发布"), err.getMessage());

        ToolInfo pub = publishedHttpTool("将下架", "{\"url\":\"http://127.0.0.1:9/x\"}", 3000);
        toolService.retire(2L, pub.id(), pub.version());
        ServiceException err2 = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(1L, 1L, pub.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err2.getCode());
        assertTrue(err2.getMessage().contains("已下架") || err2.getMessage().contains("未发布"),
                err2.getMessage());

        assertEquals(0L, runTraceCount());
        assertEquals(0L, runToolActionAuditCount(),
                "本批新建工具的 test-run/invoke 审计禁落行（全表计数在共享库失真，按 id 归因）");
    }

    @Test
    void invoke_文档类工具40001拒_不存在40401() {
        ToolInfo docDraft = toolService.create(1L, new ToolCreateInput(null, "AI规范文档", 1, "规范",
                null, "正文", null, null, null, null, null, null, null));
        ToolInfo submitted = toolService.submit(1L, docDraft.id(), docDraft.version());
        ToolInfo doc = toolService.approve(2L, submitted.id(), submitted.version());
        ServiceException docErr = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(1L, 1L, doc.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, docErr.getCode());
        assertTrue(docErr.getMessage().contains("文档类"), docErr.getMessage());

        ServiceException nf = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(1L, 1L, 999_998L, INPUT_JSON));
        assertEquals(BizErrorCode.NOT_FOUND, nf.getCode());
        assertEquals(0L, runTraceCount());
    }


    
    private ToolInfo publishedWriteHttpTool(String name, String execSpecJson) {
        ToolInfo created = toolService.create(1L, new ToolCreateInput(null, name, 2, "写入",
                null, null, null, "{\"type\":\"object\",\"x-write\":true}", null,
                1, execSpecJson, 5000, null));
        ToolInfo submitted = toolService.submit(1L, created.id(), created.version());
        return toolService.approve(2L, submitted.id(), submitted.version());
    }

    @Test
    void confirmToken_签发成功_返回token与TTL() {
        ToolInfo tool = publishedWriteHttpTool("写门签发", "{\"url\":\"http://127.0.0.1:9/x\"}");
        ToolConfirmTicket ticket = toolExecutionService.issueConfirmToken(7L, tool.id());
        assertNotNull(ticket.token(), "签发必须返回非空 token");
        assertTrue(ticket.token().length() >= 32, "token 随机强度（防枚举）: " + ticket.token());
        assertTrue(ticket.expiresInSeconds() > 0, "TTL 正数");
        assertEquals(0L, runTraceCount());
    }

    @Test
    void confirmToken_未发布工具签发拒_人话零落存() {
        ToolInfo draft = toolService.create(1L, new ToolCreateInput(null, "写门草稿", 2, "写入",
                null, null, null, "{\"type\":\"object\",\"x-write\":true}", null,
                1, "{\"url\":\"http://127.0.0.1:9/x\"}", 3000, null));
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.issueConfirmToken(1L, draft.id()));
        assertTrue(err.getMessage().contains("未发布"), err.getMessage());
    }

    @Test
    void invoke_xwrite无token_40001人话拒_零留痕零执行() {
        respond("/wo", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = publishedWriteHttpTool("写门裸调", "{\"url\":\"" + targetUrl("/wo") + "\"}");
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(7L, 4242L, tool.id(), INPUT_JSON));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("确认"), "人话必须指向确认动作: " + err.getMessage());
        assertEquals(0L, runTraceCount());
        assertEquals(0, auditCount(tool.id(), "invoke", since));
    }

    @Test
    void invoke_xwrite凭token_执行成功留痕_二次同token失效拒() {
        respond("/wt", 200, "application/json", utf8("{\"answer\":7}"), 0);
        ToolInfo tool = publishedWriteHttpTool("写门放行", "{\"url\":\"" + targetUrl("/wt") + "\"}");
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);
        ToolConfirmTicket ticket = toolExecutionService.issueConfirmToken(7L, tool.id());

        ToolTestRunResult ok = toolExecutionService.invoke(
                7L, 4242L, tool.id(), INPUT_JSON, ticket.token());
        assertTrue(ok.success(), ok.errorMessage());
        TraceRow trace = onlyTrace(tool.id());
        assertEquals("ai", trace.caller());
        assertEquals(4242L, trace.taskId());
        assertEquals(1, auditCount(tool.id(), "invoke", since));

        lastBody = null;
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(
                        7L, 4242L, tool.id(), INPUT_JSON, ticket.token()));
        assertTrue(err.getMessage().contains("确认"), err.getMessage());
        assertNull(lastBody, "token 失效后不得再打到目标（零执行）");
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE tool_id = ?",
                Integer.class, tool.id()));
    }

    @Test
    void invoke_xwrite伪造token_40001拒_零留痕() {
        respond("/wf", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = publishedWriteHttpTool("写门伪造", "{\"url\":\"" + targetUrl("/wf") + "\"}");
        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.invoke(
                        7L, 1L, tool.id(), INPUT_JSON, "deadbeef".repeat(8)));
        assertTrue(err.getMessage().contains("确认"), err.getMessage());
        assertEquals(0L, runTraceCount());
    }

    @Test
    void invoke_xwrite只读工具带token_正常执行不烧token() {
        respond("/ro", 200, "application/json", utf8("{\"ok\":1}"), 0);
        ToolInfo tool = publishedHttpTool("只读带token", "{\"url\":\"" + targetUrl("/ro") + "\"}", 5000);
        ToolTestRunResult ok = toolExecutionService.invoke(
                7L, 1L, tool.id(), INPUT_JSON, "any-token-ignored");
        assertTrue(ok.success(), ok.errorMessage());
        assertEquals(1, onlyTrace(tool.id()).success());
    }


    
    private ToolInfo genkitTool(String name, String execSpecJson) {
        return toolService.create(1L, new ToolCreateInput(null, name, 2, "造数",
                null, null, null, null, null, 4, execSpecJson, 5000, null));
    }

    @Test
    void GENKIT函数工具试跑_返回真实函数值_留痕审计恰1() {
        ToolInfo tool = genkitTool("MD5摘要", "{\"function\":\"md5\"}");
        LocalDateTime since = LocalDateTime.now().minusSeconds(5);

        ToolTestRunResult ok = toolExecutionService.testRun(1L, tool.id(), "{\"text\":\"hello\"}");
        assertTrue(ok.success(), ok.errorMessage());
        assertEquals("5d41402abc4b2a76b9719d911017c592", ok.output());

        TraceRow trace = onlyTrace(tool.id());
        assertEquals("user", trace.caller());
        assertEquals(1, trace.success());
        assertEquals(1, auditCount(tool.id(), since));
    }

    @Test
    void GENKIT随机族_同seed重放一致_异seed产出合法() {
        ToolInfo tool = genkitTool("身份证生成", "{\"function\":\"idCard\"}");

        ToolTestRunResult first = toolExecutionService.testRun(1L, tool.id(), "{\"seed\":12345}");
        ToolTestRunResult second = toolExecutionService.testRun(1L, tool.id(), "{\"seed\":12345}");
        assertTrue(first.success(), first.errorMessage());
        assertEquals(first.output(), second.output());
        assertEquals(18, first.output().length());
        assertEquals(GenKit.idCardCheckDigit(first.output().substring(0, 17)),
                first.output().charAt(17), "产出必须过 GB11643 校验位");

        ToolTestRunResult other = toolExecutionService.testRun(1L, tool.id(), "{\"seed\":999}");
        assertEquals(18, other.output().length());
        assertEquals(3, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool_invocation WHERE tool_id = ?",
                Integer.class, tool.id()));
    }

    @Test
    void GENKIT未注册函数_人话拒零留痕() {
        ToolInfo tool = genkitTool("坏函数", "{\"function\":\"noSuchFn\"}");

        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, tool.id(), "{}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("noSuchFn"), err.getMessage());
        assertTrue(err.getMessage().contains("idCard"), "人话必须含当前可用函数清单: " + err.getMessage());
        assertEquals(0L, runTraceCount());
    }

    @Test
    void GENKIT保存缺function字段_人话拒() {
        ServiceException err = assertThrows(ServiceException.class,
                () -> genkitTool("缺函数", "{\"lang\":\"js\"}"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("function"), err.getMessage());
    }

    @Test
    void GENKIT入参非JSON对象_人话拒零留痕() {
        ToolInfo tool = genkitTool("编码工具", "{\"function\":\"base64Encode\"}");

        ServiceException err = assertThrows(ServiceException.class,
                () -> toolExecutionService.testRun(1L, tool.id(), "[1,2]"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, err.getCode());
        assertTrue(err.getMessage().contains("JSON 对象"), err.getMessage());
        assertEquals(0L, runTraceCount());
    }
}
