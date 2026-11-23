package com.sixbean.testpike.core.project.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.project.api.dto.EnvHealthScanResult;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInput;
import com.sixbean.testpike.core.project.api.dto.ProjectEnvInfo;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class EnvHealthServiceTest {

    private static final String KEY_ENABLED = "env.health-check.enabled";
    private static final String KEY_INTERVAL = "env.health-interval-minutes";

    @Autowired
    private EnvHealthService envHealthService;

    @Autowired
    private ProjectEnvService envService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private HttpServer server;
    private int port;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type IN ('ENV_UNHEALTHY','ENV_RECOVERED')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE name LIKE 'r1env_%'");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id IN"
                + " (SELECT id FROM tp_project WHERE code LIKE 'r1p%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'r1p%'");
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id IN"
                + " (SELECT id FROM tp_user WHERE username LIKE 'r1_%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 'r1_%'");
        stopServer();
    }


    
    private void startServer(int loginStatus) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.createContext("/login", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(loginStatus, -1);
            exchange.close();
        });
        server.setExecutor(null);
        server.start();
        port = server.getAddress().getPort();
    }

    private void stopServer() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    
    private long[] seedProjectEnv(String baseUrl, String configJson) {
        long ownerId = insertUser("r1_owner_" + System.nanoTime() % 1000000);
        String code = "r1p" + System.nanoTime() % 1000000;
        long projectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('环境健康项目', ?, 'zh-CN', 1) RETURNING id""", Long.class, code);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)",
                projectId, ownerId);
        long envId = envService.create(projectId, new ProjectEnvInput(
                "r1env_" + code, 2, baseUrl, configJson, null, null, null));
        return new long[] {projectId, envId, ownerId};
    }

    private long insertUser(String username) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, 3, 1) RETURNING id""", Long.class, username, username);
    }

    private void setEnabled(String value) {
        jdbcTemplate.update("""
                INSERT INTO tp_sys_config (config_key, config_value, group_name, value_type, encrypt_flag)
                VALUES (?, ?, '安全', 'int', 0)
                ON CONFLICT (config_key) DO UPDATE SET config_value = EXCLUDED.config_value""",
                KEY_ENABLED, value);
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '15' WHERE config_key = ?", KEY_INTERVAL);
        configCacheHolder.put(KEY_ENABLED, value);
        configCacheHolder.put(KEY_INTERVAL, "15");
    }

    private ProjectEnvInfo envInfo(long projectId, long envId) {
        return envService.get(projectId, envId);
    }

    private Integer streak(long envId) {
        return jdbcTemplate.queryForObject(
                "SELECT health_fail_streak FROM tp_project_env WHERE id = ?", Integer.class, envId);
    }

 
    private int countScannable() {
        return jdbcTemplate.queryForObject("""
                SELECT count(*) FROM tp_project_env e JOIN tp_project p ON p.id = e.project_id
                 WHERE e.base_url IS NOT NULL AND e.base_url <> '' AND p.status <> 2""",
                Integer.class);
    }

    private List<String> notifKeys(long envId) {
        return jdbcTemplate.queryForList("""
                SELECT event_type || '|' || user_id || '|' || dedup_key FROM tp_notification
                WHERE dedup_key LIKE '%:' || ? || ':%' OR dedup_key LIKE '%:' || ? ORDER BY id""",
                String.class, String.valueOf(envId), String.valueOf(envId));
    }

    private void probeUrl(long envId, String url) {
        jdbcTemplate.update("UPDATE tp_project_env SET base_url = ? WHERE id = ?", url, envId);
    }


    
    @Test
    void healthyEnvironmentWritesStatus1() throws Exception {
        setEnabled("1");
        startServer(200);
        int baseline = countScannable();
        long[] ids = seedProjectEnv("http://127.0.0.1:" + port, null);
        EnvHealthScanResult r = envHealthService.scanAll();
        assertEquals(baseline + 1, r.scanned(), "seed 环境进扫描集（基线+1）");
        ProjectEnvInfo info = envInfo(ids[0], ids[1]);
        assertEquals(1, info.healthStatus());
        assertNotNull(info.healthCheckedAt());
        assertNull(info.healthMsg());
        assertEquals(0, streak(ids[1]));
    }

 
    @Test
    void singleFailureDoesNotAlert() {
        setEnabled("1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        ProjectEnvInfo info = envInfo(ids[0], ids[1]);
        assertEquals(2, info.healthStatus());
        assertNotNull(info.healthMsg());
        assertEquals(1, streak(ids[1]));
        assertTrue(notifKeys(ids[1]).isEmpty(), "单次异常不得告警");
    }

    
    @Test
    void twoConsecutiveFailuresAlertProjectOwner() {
        setEnabled("1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        assertEquals(2, streak(ids[1]));
        List<String> keys = notifKeys(ids[1]);
        assertEquals(1, keys.size(), "应恰好一条 ENV_UNHEALTHY: " + keys);
        assertTrue(keys.get(0).startsWith("ENV_UNHEALTHY|" + ids[2] + "|"),
                "收件人=项目 role=2 负责人: " + keys);
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE dedup_key LIKE ? LIMIT 1", String.class,
                "ENV_UNHEALTHY:" + ids[1] + ":%");
        assertTrue(content.contains("连续 2 次"), content);
        assertTrue(content.contains("健康检查异常"), content);
    }

    
    @Test
    void thirdFailureMergedNoDuplicateAlert() {
        setEnabled("1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        assertEquals(1, notifKeys(ids[1]).size(), "连续第 3 次异常不得重复告警（1h 合并）");
        ProjectEnvInfo info = envInfo(ids[0], ids[1]);
        assertEquals(2, info.healthStatus());
    }

    
    @Test
    void recoveryNotifiesPreviousRecipientsAndAllowsRealert() throws Exception {
        setEnabled("1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        probeUrl(ids[1], "http://127.0.0.1:" + startHealthyAndGetPort());
        envHealthService.scanOne(ids[1]);
        ProjectEnvInfo info = envInfo(ids[0], ids[1]);
        assertEquals(1, info.healthStatus());
        assertNull(info.healthMsg());
        assertEquals(0, streak(ids[1]));
        List<String> afterRecover = notifKeys(ids[1]);
        assertEquals(2, afterRecover.size(), "UNHEALTHY(:recovered 改名) + RECOVERED: " + afterRecover);
        assertTrue(afterRecover.stream().anyMatch(k -> k.startsWith("ENV_RECOVERED|" + ids[2] + "|")),
                String.join(";", afterRecover));
        assertTrue(afterRecover.stream().anyMatch(k -> k.endsWith(":recovered")),
                "旧告警 dedup 改名允许再告警（N3 同策略）: " + afterRecover);
        probeUrl(ids[1], "http://127.0.0.1:1/");
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        List<String> afterRealert = notifKeys(ids[1]);
        assertEquals(2, afterRealert.stream().filter(k -> k.startsWith("ENV_UNHEALTHY|")).count(),
                "恢复后再异常应可再告警（旧 :recovered 行 + 新行）: " + afterRealert);
        assertEquals(1, afterRealert.stream()
                .filter(k -> k.startsWith("ENV_UNHEALTHY|") && !k.endsWith(":recovered")).count(),
                "新告警恰一条原始键: " + afterRealert);
    }

    
    @Test
    void disabledSwitchSkipsScan() {
        setEnabled("0");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        EnvHealthScanResult r = envHealthService.scanAll();
        assertEquals(0, r.scanned());
        assertEquals(0, envInfo(ids[0], ids[1]).healthStatus(), "停用不写状态");
        setEnabled("1");
    }

 
    @Test
    void slowEnvironmentTimesOutAsUnhealthy() throws Exception {
        setEnabled("1");
        HttpServer slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/", exchange -> {
            try {
                Thread.sleep(6000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        slow.start();
        try {
            long[] ids = seedProjectEnv("http://127.0.0.1:" + slow.getAddress().getPort(), null);
            long begin = System.currentTimeMillis();
            envHealthService.scanOne(ids[1]);
            long cost = System.currentTimeMillis() - begin;
            assertTrue(cost < 10_000, "5s 超时上限，不得挂 6s+：cost=" + cost);
            ProjectEnvInfo info = envInfo(ids[0], ids[1]);
            assertEquals(2, info.healthStatus());
            assertTrue(info.healthMsg().contains("超时"), info.healthMsg());
        } finally {
            slow.stop(0);
        }
    }

    
    @Test
    void loginSmokeFailureMarksUnhealthy() throws Exception {
        setEnabled("1");
        startServer(401);
        String login = """
                {"health-login": {"path": "/login", "username": "u1", "password": "p1"}}""";
        long[] ids = seedProjectEnv("http://127.0.0.1:" + port, login);
        envHealthService.scanOne(ids[1]);
        ProjectEnvInfo info = envInfo(ids[0], ids[1]);
        assertEquals(2, info.healthStatus(), "登录冒烟 401 应判异常");
        assertTrue(info.healthMsg().contains("登录冒烟"), info.healthMsg());
        stopServer();
        startServer(200);
        probeUrl(ids[1], "http://127.0.0.1:" + port);
        envHealthService.scanOne(ids[1]);
        assertEquals(1, envInfo(ids[0], ids[1]).healthStatus());
    }

 
    @Test
    void envWithoutBaseUrlIsSkipped() {
        setEnabled("1");
        int baseline = countScannable();
        long projectId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_project (name, code, default_lang, created_by)
                VALUES ('环境健康项目', 'r1pnonurl', 'zh-CN', 1) RETURNING id""", Long.class);
        long envId = envService.create(projectId, new ProjectEnvInput(
                "r1env_nonurl", 2, null, null, null, null, null));
        EnvHealthScanResult r = envHealthService.scanAll();
        assertEquals(baseline, r.scanned(), "无 base_url 环境不进扫描（计数=基线不增）");
        assertEquals(0, envInfo(projectId, envId).healthStatus());
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE id = ?", envId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    
    @Test
    void archivedProjectEnvIsNotScanned() {
        setEnabled("1");
        int baseline = countScannable();
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        jdbcTemplate.update("UPDATE tp_project SET status = 2 WHERE id = ?", ids[0]);
        EnvHealthScanResult r = envHealthService.scanAll();
        assertEquals(baseline, r.scanned(), "归档项目环境不进定时扫描");
    }

    
    @Test
    void envUpdateResetsFailureStreak() {
        setEnabled("1");
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        envHealthService.scanOne(ids[1]);
        assertEquals(1, streak(ids[1]));
        envService.update(ids[0], ids[1], new ProjectEnvInput(
                jdbcTemplate.queryForObject("SELECT name FROM tp_project_env WHERE id=?",
                        String.class, ids[1]),
                2, "http://127.0.0.1:2/", null, null, null, null));
        assertEquals(0, streak(ids[1]), "编辑环境后连续计数清零（目标已变，旧失败不再计入）");
    }

    
    @Test
    void recoveryRenewsAllActiveAlertsForMultipleOwners() throws Exception {
        setEnabled("1");
        long ownerId2 = insertUser("r1_owner2_" + System.nanoTime() % 1000000);
        long[] ids = seedProjectEnv("http://127.0.0.1:1/", null);
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) VALUES (?, ?, 2)",
                ids[0], ownerId2);
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        List<String> alerted = jdbcTemplate.queryForList(
                "SELECT dedup_key FROM tp_notification WHERE event_type='ENV_UNHEALTHY' "
                        + "AND dedup_key LIKE ? ORDER BY user_id", String.class,
                "ENV_UNHEALTHY:" + ids[1] + ":%");
        assertEquals(2, alerted.size(), "双负责人各一条告警: " + alerted);
        probeUrl(ids[1], "http://127.0.0.1:" + startHealthyAndGetPort());
        envHealthService.scanOne(ids[1]);
        List<String> recoveredKeys = jdbcTemplate.queryForList(
                "SELECT dedup_key FROM tp_notification WHERE event_type='ENV_UNHEALTHY' "
                        + "AND dedup_key LIKE ? ORDER BY dedup_key", String.class,
                "ENV_UNHEALTHY:" + ids[1] + ":%");
        assertEquals(2, recoveredKeys.stream().filter(k -> k.endsWith(":recovered")).count(),
                "两条活跃告警键均须 :recovered 改名（残留键会永久压制该收件人再告警）: " + recoveredKeys);
        List<Long> recoveredUsers = jdbcTemplate.queryForList(
                "SELECT user_id FROM tp_notification WHERE event_type='ENV_RECOVERED' "
                        + "AND dedup_key LIKE ? ORDER BY user_id", Long.class,
                "ENV_RECOVERED:" + ids[1] + ":%");
        assertTrue(recoveredUsers.containsAll(List.of(ids[2], ownerId2)),
                "曾收 UNHEALTHY 者均收 RECOVERED: " + recoveredUsers);
        probeUrl(ids[1], "http://127.0.0.1:1/");
        envHealthService.scanOne(ids[1]);
        envHealthService.scanOne(ids[1]);
        List<String> fresh = jdbcTemplate.queryForList(
                "SELECT dedup_key FROM tp_notification WHERE event_type='ENV_UNHEALTHY' "
                        + "AND dedup_key LIKE ? AND dedup_key NOT LIKE '%:recovered' ORDER BY user_id",
                String.class, "ENV_UNHEALTHY:" + ids[1] + ":%");
        assertEquals(2, fresh.size(), "恢复后再异常双收件人均须可再告警: " + fresh);
    }

    private int startHealthyAndGetPort() throws Exception {
        startServer(200);
        return port;
    }
}
