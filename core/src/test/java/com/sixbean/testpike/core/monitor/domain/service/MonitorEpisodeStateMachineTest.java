package com.sixbean.testpike.core.monitor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import com.sixbean.testpike.core.monitor.domain.component.MonitorAlertCopy;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
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
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class MonitorEpisodeStateMachineTest {

    @Autowired
    private MonitorTargetService monitorTargetService;

    @Autowired
    private MonitorEpisodeService monitorEpisodeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long ownerId;
    private long envId;
    private com.sun.net.httpserver.HttpServer liveServer;
    private int livePort;

    @BeforeEach
    void startLiveServer() throws IOException {
        liveServer = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        liveServer.createContext("/health", ex -> {
            byte[] b = "UP".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (java.io.OutputStream os = ex.getResponseBody()) {
                os.write(b);
            }
        });
        liveServer.start();
        livePort = liveServer.getAddress().getPort();
    }

    @AfterEach
    void stopLiveServer() {
        if (liveServer != null) {
            liveServer.stop(0);
        }
    }

    @BeforeEach
    @AfterEach
    void cleanFixture() {
        jdbcTemplate.update("DELETE FROM tp_monitor_check_log WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274ep%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_episode WHERE target_id IN "
                + "(SELECT id FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274ep%'))");
        jdbcTemplate.update("DELETE FROM tp_monitor_target WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274ep%')");
        jdbcTemplate.update("DELETE FROM tp_project_env WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274ep%')");
        jdbcTemplate.update("DELETE FROM tp_notification WHERE dedup_key LIKE 'MONITOR_%'");
        jdbcTemplate.update("DELETE FROM tp_notification_pref WHERE event_type LIKE 'MONITOR_%'");
        jdbcTemplate.update("DELETE FROM tp_user_project WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE name LIKE 't274ep%')");
        jdbcTemplate.update("DELETE FROM tp_user WHERE username LIKE 't274ep%'");
        jdbcTemplate.update("DELETE FROM tp_project WHERE name LIKE 't274ep%'");
        if (projectId > 0) {
            return;
        }
        String tag = "t274ep" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                tag, tag.substring(tag.length() - 8).toUpperCase());
        projectId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project WHERE name = ?", Long.class, tag);
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 1, 1) RETURNING id
                """, Long.class, tag, tag);
        ownerId = uid;
        jdbcTemplate.update("INSERT INTO tp_user_project (project_id, user_id, role) "
                + "VALUES (?, ?, 2)", projectId, ownerId);
        jdbcTemplate.update("INSERT INTO tp_project_env (project_id, name, env_type, base_url) "
                + "VALUES (?, '主环境', 1, 'http://127.0.0.1:1/')", projectId);
        envId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_project_env WHERE project_id = ? LIMIT 1", Long.class, projectId);
    }

    
    private long deadTarget(String name) {
        return monitorTargetService.create(projectId, new MonitorTargetInput(name, envId, "HTTP",
                Map.of("url", "http://127.0.0.1:1/health"), 60, true));
    }

    private long liveTarget(String name) {
        return monitorTargetService.create(projectId, new MonitorTargetInput(name, envId, "HTTP",
                Map.of("url", "http://127.0.0.1:" + livePort + "/health"), 60, true));
    }

    
    private void scanOnce(long targetId) {
        jdbcTemplate.update("UPDATE tp_monitor_target SET next_check_at = now() - interval '1 minute' "
                + "WHERE id = ?", targetId);
        monitorTargetService.scanDueTargets();
    }

    private String state(long targetId) {
        return jdbcTemplate.queryForObject(
                "SELECT last_state FROM tp_monitor_target WHERE id = ?", String.class, targetId);
    }

    private int noticeCount(long targetId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Integer.class, targetId);
        return n == null ? 0 : n;
    }


    @Test
    void threeFailuresOpenEpisodeAndNotifyOwner() {
        long id = deadTarget("持续失败目标");
        scanOnce(id);
        scanOnce(id);
        assertEquals("UNKNOWN", state(id), "未达阈值不应迁移");
        assertEquals(0, noticeCount(id));
        scanOnce(id);
        assertEquals("DOWN", state(id));
        Long episode = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Long.class, id);
        assertNotNull(episode, "连败达阈必须开 open episode");
        List<Long> notified = jdbcTemplate.queryForList(
                "SELECT user_id FROM tp_notification WHERE event_type = 'MONITOR_DOWN' "
                        + "AND dedup_key LIKE ?", Long.class, "MONITOR_DOWN:" + id + ":%");
        assertEquals(List.of(ownerId), notified, "DOWN 告警收件人=项目负责人");
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE event_type = 'MONITOR_DOWN' "
                        + "AND dedup_key LIKE ? LIMIT 1", String.class, "MONITOR_DOWN:" + id + ":%");
        assertTrue(content.contains("监控目标「持续失败目标」"), "文案人话含目标名: " + content);
    }


    @Test
    void recoveryPassesThroughRecoveringThenUp() {
        long id = deadTarget("先死后活目标");
        scanOnce(id);
        scanOnce(id);
        scanOnce(id);
        assertEquals("DOWN", state(id));
        jdbcTemplate.update("UPDATE tp_monitor_target SET probe_config = "
                + "jsonb_set(probe_config, '{url}', ?::jsonb) WHERE id = ?",
                "\"http://127.0.0.1:" + livePort + "/health\"", id);
        scanOnce(id);
        assertEquals("RECOVERING", state(id), "第 1 次成功=过渡态");
        scanOnce(id);
        assertEquals("UP", state(id), "满 recover-successes=UP");
        Integer open = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Integer.class, id);
        assertEquals(0, open, "恢复必须关 episode");
        Integer recovered = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NOT NULL",
                Integer.class, id);
        assertEquals(1, recovered);
        Integer recNotices = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'MONITOR_RECOVERED' "
                        + "AND dedup_key LIKE ?", Integer.class, "MONITOR_RECOVERED:" + id + ":%");
        assertEquals(1, recNotices, "恢复通知发给曾收告警者");
        Integer activeKeys = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE ? "
                        + "AND dedup_key NOT LIKE '%:recovered'", Integer.class,
                "MONITOR_DOWN:" + id + ":%");
        assertEquals(0, activeKeys, "活跃 DOWN 键必须改名，再次故障可再告警");
    }


    @Test
    void onlineSlowWindowBreachOpensDown() {
        long id = monitorTargetService.create(projectId, new MonitorTargetInput("慢档劣化", envId,
                "ONLINE", Map.of("url", "http://127.0.0.1:1/health"), 60, true));
        jdbcTemplate.update("INSERT INTO tp_monitor_check_log (target_id, outcome, checked_at) "
                + "SELECT ?, 'ok', now() - (n || ' minute')::interval FROM generate_series(1,3) n", id);
        jdbcTemplate.update("INSERT INTO tp_monitor_check_log (target_id, outcome, detail, checked_at) "
                + "SELECT ?, 'fail', '慢', now() - (n || ' minute')::interval FROM generate_series(1,9) n", id);
        scanOnce(id);
        assertEquals("DOWN", state(id), "ONLINE 慢档窗口劣化应开 DOWN");
        Integer episodes = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Integer.class, id);
        assertEquals(1, episodes);
    }

    @Test
    void onlineSlowWindowBelowMinSamplesDoesNotBreach() {
        long id = monitorTargetService.create(projectId, new MonitorTargetInput("样本不足", envId,
                "ONLINE", Map.of("url", "http://127.0.0.1:1/health"), 60, true));
        jdbcTemplate.update("INSERT INTO tp_monitor_check_log (target_id, outcome, checked_at) "
                + "SELECT ?, 'fail', now() - (n || ' minute')::interval FROM generate_series(1,5) n", id);
        scanOnce(id);
        assertEquals("UNKNOWN", state(id), "样本 < slow-min-samples 禁凭占比误判 DOWN");
    }

    // ---------- ④ FLAPPING ----------

    @Test
    void thirdEpisodeInWindowSwitchesToFlapping() {
        long id = deadTarget("抖动目标");
        jdbcTemplate.update("INSERT INTO tp_monitor_episode (target_id, started_at, recovered_at, cause_summary) "
                + "SELECT ?, now() - (n || ' minute')::interval, now() - ((n-1) || ' minute')::interval, '历史抖动' "
                + "FROM generate_series(2,3) n", id);
        scanOnce(id);
        scanOnce(id);
        scanOnce(id);
        assertEquals("FLAPPING", state(id), "窗口内第 3 个 episode 应升 FLAPPING");
        Integer flap = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'MONITOR_FLAPPING' "
                        + "AND dedup_key LIKE ?", Integer.class, "MONITOR_FLAPPING:" + id + ":%");
        assertEquals(1, flap, "FLAPPING 单独人话告警");
        String content = jdbcTemplate.queryForObject(
                "SELECT content FROM tp_notification WHERE event_type = 'MONITOR_FLAPPING' LIMIT 1",
                String.class);
        assertTrue(content.contains("间歇性不可用"), "文案含处置建议: " + content);
        assertEquals(1, noticeCount(id));
    }


    @Test
    void renotifySuppressedWithinInterval() {
        long id = deadTarget("刷屏防护");
        scanOnce(id);
        scanOnce(id);
        scanOnce(id);
        assertEquals("DOWN", state(id));
        scanOnce(id);
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE ?", Integer.class,
                "MONITOR_DOWN:" + id + ":%");
        assertEquals(1, rows, "间隔内不得刷屏");
        Integer notice = jdbcTemplate.queryForObject(
                "SELECT notice_count FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Integer.class, id);
        assertEquals(1, notice);
    }

    @Test
    void renotifyAfterIntervalBumpsNoticeCount() {
        long id = deadTarget("超窗重提醒");
        scanOnce(id);
        scanOnce(id);
        scanOnce(id);
        jdbcTemplate.update("UPDATE tp_monitor_episode SET last_notified_at = now() - interval '61 minutes' "
                + "WHERE target_id = ? AND recovered_at IS NULL", id);
        scanOnce(id);
        Integer notice = jdbcTemplate.queryForObject(
                "SELECT notice_count FROM tp_monitor_episode WHERE target_id = ? AND recovered_at IS NULL",
                Integer.class, id);
        assertEquals(2, notice, "超间隔 notice_count+1");
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE 'MONITOR_DOWN:" + id + ":%'",
                Integer.class);
        assertEquals(2, rows, "重提醒 dedup 键带时间后缀=新行");
    }


    @Test
    void silencedTargetRecordsTransitionButSuppressesNotice() {
        long id = deadTarget("发布静默");
        monitorTargetService.silence(id, 10, "版本发布窗口");
        jdbcTemplate.update("UPDATE tp_monitor_target SET consecutive_failures = 3, last_cause = '连接超时' WHERE id = ?", id);
        monitorEpisodeService.evaluate(id);
        assertEquals("DOWN", state(id), "静默期状态迁移与 episode 照记");
        assertEquals(1, noticeCount(id));
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE dedup_key LIKE ?", Integer.class,
                "MONITOR_DOWN:" + id + ":%");
        assertEquals(0, rows, "静默期通知抑制");
    }


    @Test
    void lostWatchdogAlertsAdminOnceUntilHeartbeat() {
        long adminId = seedAdminUser();
        assertEquals(0, monitorEpisodeService.alertIfStalled(-1), "从未扫描=盲区不判失联（如实注记）");
        monitorEpisodeService.markScanHeartbeat();
        int sent = monitorEpisodeService.alertIfStalled(-1);
        assertTrue(sent >= 1);
        Integer first = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_notification WHERE event_type = 'MONITOR_LOST' AND user_id = ?",
                Integer.class, adminId);
        assertEquals(1, first);
        assertEquals(0, monitorEpisodeService.alertIfStalled(-1));
        assertEquals(0, monitorEpisodeService.alertIfStalled(3600));
        assertTrue(monitorEpisodeService.alertIfStalled(-1) >= 1, "解除后可再告警");
    }

    private long seedAdminUser() {
        String tag = "t274epadm" + System.nanoTime();
        Long uid = jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, '$2a$10$x', ?, 4, 1) RETURNING id
                """, Long.class, tag, tag);
        return uid;
    }


    @Test
    void alertCopyMasksIpsAndPasswords() {
        String masked = MonitorAlertCopy.maskHuman(
                "登录 http://10.20.30.40/login 失败 password=****** user.name=bob");
        assertTrue(!masked.contains("10.20.30.40"), "IP 必须打码: " + masked);
        assertTrue(!masked.contains("Secr3t"), "口令必须打码: " + masked);
        assertTrue(masked.contains("10.20.*.*"), "IP 打码保留前两段的网络定位可读性: " + masked);
    }

    @Test
    void eventDictRegistersFourMonitorKeys() {
        for (String key : List.of("MONITOR_DOWN", "MONITOR_RECOVERED", "MONITOR_FLAPPING", "MONITOR_LOST")) {
            assertTrue(NotificationEventDict.isKnown(key), key + " 必须登记进字典");
            assertTrue(NotificationEventDict.isDefaultEnabled(key), key + " 默认订阅");
        }
        assertTrue(NotificationEventDict.isForced("MONITOR_DOWN"), "DOWN=执行底线强制订阅（仿 ENV_UNHEALTHY）");
        assertEquals(false, NotificationEventDict.isForced("MONITOR_RECOVERED"));
        assertEquals(false, NotificationEventDict.isForced("MONITOR_FLAPPING"));
        assertEquals(false, NotificationEventDict.isForced("MONITOR_LOST"));
    }

    @Test
    void newConfigKeysHaveDefaults() {
        assertEquals("2", monitorEpisodeService.configValue("monitor.recover-successes"));
        assertEquals("80", monitorEpisodeService.configValue("monitor.slow-online-rate-threshold"));
        assertEquals("30", monitorEpisodeService.configValue("monitor.flap-window-minutes"));
        assertEquals("3", monitorEpisodeService.configValue("monitor.flap-episode-count"));
        assertEquals("60", monitorEpisodeService.configValue("monitor.renotify-interval-minutes"));
        assertEquals("2", monitorEpisodeService.configValue("monitor.stall-multiplier"));
        assertEquals("15", monitorEpisodeService.configValue("monitor.scan-heartbeat-seconds"));
    }
}
