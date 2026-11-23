package com.sixbean.testpike.core.monitor.domain.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorTargetServiceTest {

    @Autowired
    private MonitorTargetService monitorTargetService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final ObjectMapper mapper = new ObjectMapper();

    private long projectId;
    private long envId;
    private com.sun.net.httpserver.HttpServer stableServer;
    private int stablePort;

    @BeforeEach
    void startStableServer() throws IOException {
        stableServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        stableServer.createContext("/health", ex -> {
            byte[] b = "UP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        stableServer.start();
        stablePort = stableServer.getAddress().getPort();
    }

    @AfterEach
    void stopStableServer() {
        if (stableServer != null) {
            stableServer.stop(0);
        }
    }

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274fix%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274fix%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274fix%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274fix%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274fix%'");
        if (projectId > 0) {
            return;
        }
        String tag = "t274fix" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                tag, tag.substring(tag.length() - 8).toUpperCase());
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ?", Long.class, tag);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '主环境', 1, 'http://127.0.0.1:1/')", projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? LIMIT 1", Long.class, projectId);
    }

    private MonitorTargetInput httpTarget(String name) {
        return new MonitorTargetInput(name, envId, "HTTP",
                Map.of("url", "http://127.0.0.1:" + stablePort + "/health"), 60, true);
    }


    @Test
    void createRejectsUnknownTargetType() {
        MonitorTargetInput input = new MonitorTargetInput("非法类型", envId, "FTP", Map.of(), 60, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, input));
        assertTrue(ex.getMessage().contains("FTP"), "人话文案应含非法类型值: " + ex.getMessage());
    }

    @Test
    void createRejectsIntervalBelowHardFloor() {
        MonitorTargetInput input = new MonitorTargetInput("秒探轰炸", envId, "HTTP",
                Map.of("url", "http://x/"), 5, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, input));
        assertTrue(ex.getMessage().contains("60"), "文案应给出下限值: " + ex.getMessage());
    }

    @Test
    void createRejectsDuplicateNameInProject() {
        monitorTargetService.create(projectId, httpTarget("重名目标"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, httpTarget("重名目标")));
        assertEquals(40904, ex.getCode());
    }

    @Test
    void createRejectsForeignEnv() {
        MonitorTargetInput input = new MonitorTargetInput("越权目标", 999999999L, "HTTP",
                Map.of("url", "http://x/"), 60, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, input));
        assertEquals(40301, ex.getCode());
    }

    @Test
    void loginTargetMustUseCredentialReference() {
        MonitorTargetInput plain = new MonitorTargetInput("明文口令", envId, "LOGIN",
                Map.of("url", "http://x/login", "username", "u1", "password", "***"), 60, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, plain));
        assertTrue(ex.getMessage().contains("@cred/"), "文案应指向凭据引用: " + ex.getMessage());

        MonitorTargetInput ref = new MonitorTargetInput("引用口令", envId, "LOGIN",
                Map.of("url", "http://x/login", "credential", "@cred/sso-row-1"), 60, true);
        long id = monitorTargetService.create(projectId, ref);
        assertTrue(id > 0);
    }

    /**
     * Login-state direct read (design doc M1 form two) is the other legal LOGIN
     * shape: it carries a ledger row name plus a protected check URL instead of
     * an account replay. The save gate must accept the shape; row existence and
     * replayability stay judged at probe time by the single authority
     * (SessionStateSupport.probeAuthHeader), so no second verdict lives here.
     */
    @Test
    void loginTargetAcceptsStateReadProbeShape() {
        MonitorTargetInput stateRead = new MonitorTargetInput("登录态直读目标", envId, "LOGIN",
                Map.of("mode", "state-read", "stateName", "m1-token",
                        "checkUrl", "http://127.0.0.1:1/protected"), 60, true);
        long id = monitorTargetService.create(projectId, stateRead);
        assertTrue(id > 0);
    }

    /** Direct read without both keys cannot ever probe: refuse at save, name both keys. */
    @Test
    void loginTargetStateReadRequiresStateNameAndCheckUrl() {
        MonitorTargetInput missing = new MonitorTargetInput("直读缺键", envId, "LOGIN",
                Map.of("mode", "state-read", "stateName", "m1-token"), 60, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, missing));
        assertTrue(ex.getMessage().contains("stateName"), "verdict must name the row key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("checkUrl"), "verdict must name the url key: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("登录态账本"),
                "verdict must point at the ledger page where the row is picked: " + ex.getMessage());
    }

    /** The plaintext-secret red line holds for the direct-read shape too. */
    @Test
    void loginTargetStateReadRefusesPlaintextPassword() {
        MonitorTargetInput smuggled = new MonitorTargetInput("直读夹带明文", envId, "LOGIN",
                Map.of("mode", "state-read", "stateName", "m1-token",
                        "checkUrl", "http://127.0.0.1:1/protected", "password", "***"), 60, true);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> monitorTargetService.create(projectId, smuggled));
        assertTrue(ex.getMessage().contains("明文"),
                "verdict must name the plaintext smuggle: " + ex.getMessage());
    }


    @Test
    void scanDueTargetsSkipsDisabledAndSilenced() {
        long on = monitorTargetService.create(projectId, httpTarget("在跑目标"));
        MonitorTargetInput offInput = httpTarget("停用目标");
        long off = monitorTargetService.create(projectId, offInput);
        monitorTargetService.updateEnabled(off, false);
        jdbcTemplate.update("UPDATE tp_monitor_target SET next_check_at = now() - interval '1 minute' "
                + "WHERE id IN (?, ?)", on, off);
        monitorTargetService.silence(on, 10, "发布窗口");

        monitorTargetService.scanDueTargets();

        List<String> outcomes = jdbcTemplate.queryForList(
                "SELECT outcome FROM tp_monitor_check_log WHERE target_id = ?", String.class, on);
        assertTrue(outcomes.isEmpty(), "静默窗口内不应产生探测留痕: " + outcomes);
        List<String> offOutcomes = jdbcTemplate.queryForList(
                "SELECT outcome FROM tp_monitor_check_log WHERE target_id = ?", String.class, off);
        assertTrue(offOutcomes.isEmpty(), "停用目标不应被探测: " + offOutcomes);
    }

    @Test
    void wiredProbeRecordsHonestFailNeverDeferred() {
        long id = monitorTargetService.create(projectId,
                new MonitorTargetInput("待接线探针", envId, "CERT", Map.of(), 60, true));
        jdbcTemplate.update("UPDATE tp_monitor_target SET next_check_at = now() - interval '1 minute' "
                + "WHERE id = ?", id);

        int scanned = monitorTargetService.scanDueTargets();
        assertEquals(1, scanned);

        String outcome = jdbcTemplate.queryForObject(
                "SELECT outcome FROM tp_monitor_check_log WHERE target_id = ? LIMIT 1",
                String.class, id);
        assertEquals("fail", outcome, "接线探针无观测必须诚实 fail 留痕，禁 deferred/禁假绿 ok");
        Long nextIsFuture = jdbcTemplate.queryForObject(
                "SELECT CASE WHEN next_check_at > now() THEN 1 ELSE 0 END "
                        + "FROM tp_monitor_target WHERE id = ?", Long.class, id);
        assertEquals(1L, nextIsFuture, "探测后应排下一次时间");
    }


    @Test
    void backoffLevelLadderFollowsConfigKeys() {
        assertEquals(60, monitorTargetService.intervalSecondsForLevel(0));
        assertEquals(300, monitorTargetService.intervalSecondsForLevel(1));
        assertEquals(900, monitorTargetService.intervalSecondsForLevel(2));
        assertEquals(900, monitorTargetService.intervalSecondsForLevel(5));
    }

    @Test
    void stableCyclesAdvanceBackoffLevel() {
        long id = monitorTargetService.create(projectId, httpTarget("退避升档"));
        for (int i = 0; i < 3; i++) {
            jdbcTemplate.update("UPDATE tp_monitor_target SET next_check_at = now() - interval '1 minute' "
                    + "WHERE id = ?", id);
            monitorTargetService.scanDueTargets();
        }
        Integer level = jdbcTemplate.queryForObject(
                "SELECT backoff_level FROM tp_monitor_target WHERE id = ?", Integer.class, id);
        assertEquals(1, level, "连续 3 个稳定周期应升一档");
    }

    @Test
    void failureResetsBackoffLevel() {
        long id = monitorTargetService.create(projectId, httpTarget("失败复位"));
        jdbcTemplate.update("UPDATE tp_monitor_target SET backoff_level = 2, stable_cycles = 2, "
                + "next_check_at = now() - interval '1 minute' WHERE id = ?", id);
        monitorTargetService.recordProbeFailure(id, "连接被拒（127.0.0.1:1）");
        Integer level = jdbcTemplate.queryForObject(
                "SELECT backoff_level FROM tp_monitor_target WHERE id = ?", Integer.class, id);
        assertEquals(0, level, "任一失败立即复位 0 档");
    }

    @Test
    void envVersionBumpResetsBackoffLevel() {
        long id = monitorTargetService.create(projectId, httpTarget("档案变更复位"));
        jdbcTemplate.update("UPDATE tp_monitor_target SET backoff_level = 2, stable_cycles = 2 WHERE id = ?", id);
        jdbcTemplate.update("UPDATE tp_project_env SET version = version + 1 WHERE id = ?", envId);

        int reset = monitorTargetService.resetStaleSnapshots();
        assertTrue(reset >= 1, "环境档案 version 变化应触发复位");
        Integer level = jdbcTemplate.queryForObject(
                "SELECT backoff_level FROM tp_monitor_target WHERE id = ?", Integer.class, id);
        assertEquals(0, level);
    }


    @Test
    void updateResetsSchedulingState() {
        long id = monitorTargetService.create(projectId, httpTarget("编辑复位"));
        jdbcTemplate.update("UPDATE tp_monitor_target SET backoff_level = 2, stable_cycles = 1, "
                + "consecutive_failures = 3 WHERE id = ?", id);
        MonitorTargetInput edited = new MonitorTargetInput("编辑复位", envId, "HTTP",
                Map.of("url", "http://y/"), 120, true);
        monitorTargetService.update(id, edited);
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT backoff_level, stable_cycles, consecutive_failures FROM tp_monitor_target WHERE id = ?",
                id);
        assertEquals(0, ((Number) row.get("backoff_level")).intValue());
        assertEquals(0, ((Number) row.get("stable_cycles")).intValue());
        assertEquals(0, ((Number) row.get("consecutive_failures")).intValue());
    }

    @Test
    void configKeysDriveWindowParams() {
        assertNotNull(monitorTargetService.configValue("monitor.fast-window-minutes"));
        assertNotNull(monitorTargetService.configValue("monitor.fast-fail-threshold"));
        assertNotNull(monitorTargetService.configValue("monitor.slow-window-minutes"));
        assertNotNull(monitorTargetService.configValue("monitor.slow-min-samples"));
        assertNotNull(monitorTargetService.configValue("monitor.backoff-ladder-seconds"));
        assertNotNull(monitorTargetService.configValue("monitor.stable-cycles-to-advance"));
        assertNotNull(monitorTargetService.configValue("monitor.min-interval-seconds"));
        assertEquals("3", monitorTargetService.configValue("monitor.fast-fail-threshold"));
    }

    @Test
    void silenceRequiresReasonAndClearsBack() {
        long id = monitorTargetService.create(projectId, httpTarget("静默目标"));
        monitorTargetService.silence(id, 30, "版本发布");
        String reason = jdbcTemplate.queryForObject(
                "SELECT silence_reason FROM tp_monitor_target WHERE id = ?", String.class, id);
        assertEquals("版本发布", reason);
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT silence_until FROM tp_monitor_target WHERE id = ?", Object.class, id));

        assertThrows(ServiceException.class, () -> monitorTargetService.silence(id, 30, "  "));

        monitorTargetService.clearSilence(id);
        assertNull(jdbcTemplate.queryForObject(
                "SELECT silence_until FROM tp_monitor_target WHERE id = ?", Object.class, id));
    }
}
