package com.sixbean.testpike.core.defects.domain.component;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.batch.domain.component.AutoDefectSupport;
import com.sixbean.testpike.core.cases.api.dto.CaseCreateInput;
import com.sixbean.testpike.core.cases.api.dto.CaseInfo;
import com.sixbean.testpike.core.cases.domain.service.CaseService;
import com.sixbean.testpike.core.defects.domain.service.DefectLibraryService;
import com.sixbean.testpike.core.project.domain.service.ProjectService;
import com.sixbean.testpike.core.support.ProjectFixtureRig;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class JiraSyncSupportTest {

    @Autowired
    private JiraSyncSupport jiraSyncSupport;
    @Autowired
    private AutoDefectSupport autoDefectSupport;
    @Autowired
    private CaseService caseService;
    @Autowired
    private ProjectService projectService;
    @Autowired
    private DefectLibraryService defectLibraryService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private HttpServer jiraStub;
    private int jiraPort;
    
    private final List<String> issueCreates = new CopyOnWriteArrayList<>();
    
    private final List<String> attachmentPosts = new CopyOnWriteArrayList<>();
    
    private final AtomicInteger issueCreateStatus = new AtomicInteger(201);
    
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();

    /**
 * R37 fixture-scoped teardown : the old blanket TRUNCATE
 * wiped shipped projects, users and notifications on the shared dev
 * database. tp_comment is polymorphic with no FK into tp_defect, so its
 * join purge runs BEFORE the project sweep (shared helper, R40). The
 * fixed project codes self-heal crashed-run residue. tp_sys_config rows
 * are reset, not wiped: restoreConfig below returns them to defaults.
 */
    private final List<Long> trackedProjects = new ArrayList<>();

    @BeforeEach
    @AfterEach
    void cleanFixtureRows() {
        SharedDbFixtureSweep.defectCommentRowsOfProjects(jdbcTemplate, trackedProjects);
        SharedDbFixtureSweep.byProjectList(jdbcTemplate, trackedProjects);
        trackedProjects.clear();
        restoreConfig();
    }

    private void restoreConfig() {
        upsertConfig("jira.sync.enabled", "0", "int");
        upsertConfig("jira.sync.base-url", "", "string");
        upsertConfig("jira.sync.token", "", "string");
        upsertConfig("jira.sync.timeout-seconds", "5", "int");
        upsertConfig("defect.auto-create.enabled", "1", "int");
        upsertConfig("defect.auto-create.dedup-window", "24", "int");
    }

    private void upsertConfig(String key, String value, String type) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag, description)
                VALUES (?, ?, '系统', ?, 0, '测试期临时配置值')
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value
                """, key, value, type);
    }

    @BeforeEach
    void startJiraStub() throws IOException {
        issueCreates.clear();
        attachmentPosts.clear();
        authHeaders.clear();
        issueCreateStatus.set(201);
        jiraStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        jiraStub.createContext("/rest/api/2/issue", this::handleIssueApi);
        jiraStub.start();
        jiraPort = jiraStub.getAddress().getPort();
    }

    @AfterEach
    void stopJiraStub() {
        if (jiraStub != null) {
            jiraStub.stop(0);
        }
    }

    private void handleIssueApi(HttpExchange exchange) throws IOException {
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
        String path = exchange.getRequestURI().getPath();
        if (path.endsWith("/attachments")) {
            attachmentPosts.add(path + "\n" + body);
            byte[] ok = "{\"attachments\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
        } else {
            issueCreates.add(body);
            int code = issueCreateStatus.get();
            byte[] resp = code == 201
                    ? "{\"id\":\"10001\",\"key\":\"QA-7\"}".getBytes(StandardCharsets.UTF_8)
                    : "{\"errorMessages\":[\"boom\"]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(code, resp.length);
            exchange.getResponseBody().write(resp);
        }
        exchange.close();
    }

    private void enableSync() {
        upsertConfig("jira.sync.enabled", "1", "int");
        upsertConfig("jira.sync.base-url", "http://127.0.0.1:" + jiraPort, "string");
        upsertConfig("jira.sync.token", "test-jira-token", "string");
    }

    private long project(String code, String jiraProjectId) {
        long id = ProjectFixtureRig.createSelfHeal(jdbcTemplate, projectService, 1L,
                trackedProjects, "项目" + code, code);
        if (jiraProjectId != null) {
            jdbcTemplate.update("UPDATE tp_project SET jira_project_id = ? WHERE id = ?", jiraProjectId, id);
        }
        // The service-path create registers creator id=1 as project owner;
        // drop it so the suite never resolves a shipped account as assignee
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id = ? AND user_id = 1", id);
        return id;
    }

    private long caseOf(long projectId, String title) {
        CaseInfo info = caseService.create(1L, new CaseCreateInput(projectId, title, "前置", "预期",
                1, 2, "web", 3, null, 1, null, List.of()));
        return info.id();
    }

    
    private long failingBatch(long projectId, long caseId) {
        Long batchId = jdbcTemplate.queryForObject(
                "INSERT INTO tp_exec_batch (project_id, batch_no, name, status, type) "
                        + "VALUES (?, ?, ?, 4, 1) RETURNING id",
                Long.class, projectId, "B-" + System.nanoTime(), "JIRA 同步批次");
        assert batchId != null;
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, error_type, error_detail,
                                          steps_timeline)
                VALUES (?, ?, ?, 4, 2, '断言失败 expected=200 actual=500 elapsed 12ms',
                        '[{"stepNo":3,"status":"fail"}]'::jsonb)
                """, projectId, caseId, batchId);
        return batchId;
    }

    private void fireHook(long batchId) {
        autoDefectSupport.onBatchPartialFailed(batchId);
    }


    @Test
    void 配置项目自动建单_真同步创建_回填key与成功态() {
        enableSync();
        long pid = project("J1", "QA");
        long cid = caseOf(pid, "登录接口断言");
        fireHook(failingBatch(pid, cid));

        Long defectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_defect WHERE source = 2 AND project_id = ?", Long.class, pid);
        assertNotNull(defectId, "站内单必须先立");
        assertEquals(1, issueCreates.size(), "出站创建应恰 1 次");
        String create = issueCreates.get(0);
        assertTrue(create.contains("auto-defect"), "JIRA 单须带 auto-defect 标记: " + create);
        assertTrue(create.contains("\"QA\""), "创建体须含项目 jira_project_id 的 project.key: " + create);
        assertTrue(authHeaders.stream().anyMatch(h -> h != null && h.contains("test-jira-token")),
                "出站须带配置 token 认证头: " + authHeaders);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT external_key, jira_sync_status, jira_sync_error FROM tp_defect WHERE id = ?", defectId);
        assertEquals("QA-7", row.get("external_key"), "成功回填 JIRA 返回 issue key");
        assertEquals(1, ((Number) row.get("jira_sync_status")).intValue());
        assertNull(row.get("jira_sync_error"));
    }

    @Test
    void 附件链路_截图与协议快照上传() {
        enableSync();
        long pid = project("J2", "QA");
        long cid = caseOf(pid, "带证据用例");
        long batchId = failingBatch(pid, cid);
        long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_exec_task WHERE batch_id = ?", Long.class, batchId);
        jdbcTemplate.update("""
                INSERT INTO tp_exec_step_snapshot (task_id, step_no, kind, truncated, raw_bytes,
                        request_snapshot, response_snapshot, assertions_snapshot)
                VALUES (?, 3, 'http.request', 0, 120, ?, ?, ?)
                """, taskId, gzipJson("{\"method\":\"POST\",\"url\":\"/api/login\"}"),
                gzipJson("{\"status\":500}"), gzipJson("{\"expected\":\"200\",\"actual\":\"500\"}"));
        jdbcTemplate.update("UPDATE tp_exec_task SET screenshots_json = '[\"exec/1/step-3.png\"]'::jsonb WHERE id = ?",
                taskId);

        fireHook(batchId);

        assertEquals(1, issueCreates.size());
        assertTrue(attachmentPosts.stream().anyMatch(a -> a.contains("protocol-snapshot")),
                "协议快照须作为附件上传: " + attachmentPosts.size() + " 件");
        assertTrue(attachmentPosts.stream().allMatch(a -> a.contains("QA-7")),
                "附件路径挂 issue key");
        Long defectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_defect WHERE source = 2 AND project_id = ?", Long.class, pid);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT jira_sync_status FROM tp_defect WHERE id = ?", Integer.class, defectId),
                "截图对象缺失只跳过附件，不改写同步成功态");
    }


    @Test
    void 未配JIRA项目_仅站内不抛_零出站() {
        enableSync();
        long pid = project("J3", null);   // jira_project_id NULL
        long cid = caseOf(pid, "无外部映射用例");
        fireHook(failingBatch(pid, cid));

        Long defectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_defect WHERE source = 2 AND project_id = ?", Long.class, pid);
        assertNotNull(defectId, "站内单照常");
        assertEquals(0, issueCreates.size(), "未配项目零出站");
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT jira_sync_status FROM tp_defect WHERE id = ?", Integer.class, defectId));
    }

    @Test
    void 同步开关关或baseUrl空_零出站() {
        long pid = project("J4", "QA");
        long cid = caseOf(pid, "开关关用例");
        fireHook(failingBatch(pid, cid));
        assertEquals(0, issueCreates.size(), "enabled=0 零出站（默认关=存量零变化）");

        upsertConfig("jira.sync.enabled", "1", "int");
        long cid2 = caseOf(pid, "地址空用例");
        fireHook(failingBatch(pid, cid2));
        assertEquals(0, issueCreates.size(), "base-url 空零出站");
    }


    @Test
    void JIRA返回500_站内单成功_同步态retry标记不阻断() {
        enableSync();
        issueCreateStatus.set(500);
        long pid = project("J5", "QA");
        long cid = caseOf(pid, "JIRA炸了用例");
        fireHook(failingBatch(pid, cid));

        Long defectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_defect WHERE source = 2 AND project_id = ?", Long.class, pid);
        assertNotNull(defectId, "站内单必须成功");
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT external_key, jira_sync_status, jira_sync_error FROM tp_defect WHERE id = ?", defectId);
        assertEquals(2, ((Number) row.get("jira_sync_status")).intValue(), "失败=同步态 2 待重试标记");
        assertNotNull(row.get("jira_sync_error"), "人话错误留痕");
        assertTrue(String.valueOf(row.get("jira_sync_error")).length() <= 500);
        assertNull(row.get("external_key"), "失败不得回填 key");
    }

    @Test
    void JIRA超时_同步态2_预算热更随动() throws Exception {
        enableSync();
        upsertConfig("jira.sync.timeout-seconds", "1", "int");
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/rest/api/2/issue", ex -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            byte[] b = "{}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(201, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        slow.start();
        try {
            upsertConfig("jira.sync.base-url", "http://127.0.0.1:" + slow.getAddress().getPort(), "string");

            long pid = project("J6", "QA");
            long cid = caseOf(pid, "JIRA超时用例");
            fireHook(failingBatch(pid, cid));

            Long defectId = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_defect WHERE source = 2 AND project_id = ?", Long.class, pid);
            assertNotNull(defectId, "超时站内单照常");
            assertEquals(2, jdbcTemplate.queryForObject(
                    "SELECT jira_sync_status FROM tp_defect WHERE id = ?", Integer.class, defectId),
                    "超时=降级同步态 2（域 1~60 键热更即随动）");
        } finally {
            slow.stop(0);
        }
    }


    @Test
    void 人工建单与追评链路_零同步出站() {
        enableSync();
        long pid = project("J7", "QA");
        defectLibraryService.create(1L, new com.sixbean.testpike.core.defects.api.dto.DefectCreateInput(
                pid, null, null, "人工单", "描述", 2, null, null, null));
        assertEquals(0, issueCreates.size(), "人工单不进自动同步面");

        long cid = caseOf(pid, "追评用例");
        long batchId = failingBatch(pid, cid);
        fireHook(batchId);
        assertEquals(1, issueCreates.size(), "首建出站 1 次");
        jdbcTemplate.update("""
                INSERT INTO tp_exec_task (project_id, case_id, batch_id, status, error_type, error_detail,
                                          steps_timeline)
                VALUES (?, ?, ?, 4, 2, '断言失败 expected=200 actual=500 elapsed 12ms',
                        '[{"stepNo":3,"status":"fail"}]'::jsonb)
                """, pid, cid, batchId);
        fireHook(batchId);
        assertEquals(1, issueCreates.size(), "追评不重投出站（去重=不追单不同步）");
    }

    
    private static byte[] gzipJson(String text) {
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(out)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
