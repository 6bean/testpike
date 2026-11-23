package com.sixbean.testpike.core.executor.domain.service;

import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInfo;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.api.dto.ExecutorRegisterInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.domain.service.UserService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
@TestPropertySource(properties = "testpike.scheduling.enabled=false")
class ExecutorOfflineTest {

    private static final String OFFLINE_SECONDS_KEY = "testpike.executor.offline-seconds";

    @Autowired
    private ExecutorService executorService;

    @Autowired
    private ConfigService configService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private UserService userService;

    
    private Long adminUserId;

    /**
 * R37 fixture-scoped teardown : the suite owns its
 * ex-offline-% executors and the EXECUTOR_* notification/pref rows created
 * after the window floor. The old blanket TRUNCATE wiped executor rows
 * held by concurrent suites (dev-slot runner rows included) and every
 * shipped notification on the shared dev database. -1 = first hook run,
 * no window exists yet, so the row deletes stay no-ops.
 */
    private long notifyFloor = -1L;
    private long prefFloor = -1L;

    @BeforeEach
    @AfterEach
    void cleanTables() {
        if (notifyFloor >= 0) {
            jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type"
                    + " IN ('EXECUTOR_OFFLINE','EXECUTOR_ONLINE') AND id > ?", notifyFloor);
        }
        if (prefFloor >= 0) {
            jdbcTemplate.update("DELETE FROM tp_notification_pref WHERE id > ?", prefFloor);
        }
        jdbcTemplate.update("DELETE FROM tp_executor WHERE name LIKE 'ex-offline-%'");
        notifyFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification");
        prefFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_notification_pref");
        adminUserId = jdbcTemplate.query(
                "SELECT id FROM tp_user WHERE role = 4 AND deleted = 0 ORDER BY id LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null);
        if (adminUserId == null) {
            jdbcTemplate.update("DELETE FROM tp_user WHERE username = 'tp_offline_admin'");
            adminUserId = userService.create(null, new UserCreateInput(
                    "tp_offline_admin", "Test@123456", "执行器离线测试管理员", null, null, 4));
        }
    }

    private long register(String name) {
        return executorService.register(new ExecutorRegisterInput(
                name, null, null, "web", "chromium", 2, "1.0.0", 0));
    }

    
    private void simulateHeartbeatStop(long executorId) {
        jdbcTemplate.update(
                "UPDATE tp_executor SET last_heartbeat = now() - interval '40 seconds' WHERE id = ?",
                executorId);
    }

    /**
     * Read side of the windowed teardown: only rows created after the current
     * floor belong to this run (shared dev DB, no unscoped counts — the same
     * id-watermark shape the defect-family conversion settled on).
     */
    private List<NotificationInfo> notificationsOf(String eventType) {
        return jdbcTemplate.query("""
                SELECT id, user_id, project_id, event_type, channel, title, content, status, read_flag,
                       to_char(created_at, 'YYYY-MM-DD HH24:MI:SS'), dedup_key
                FROM tp_notification WHERE event_type = ? AND id > ? ORDER BY id
                """, (rs, i) -> new NotificationInfo(
                rs.getLong("id"), rs.getLong("user_id"), (Long) rs.getObject("project_id"),
                rs.getString("event_type"), rs.getString("channel"), rs.getString("title"),
                rs.getString("content"), rs.getInt("status"), rs.getInt("read_flag"),
                rs.getString(10), rs.getString("dedup_key")), eventType, notifyFloor);
    }

    @Test
    void offline_心跳超时_告警管理员并置离线() {
        assertNotNull(adminUserId, "dev 库必须存在系统管理员（种子 admin）");
        long id = register("ex-offline-01");

        simulateHeartbeatStop(id);
        int offline = executorService.detectOfflineAndNotify();

        assertEquals(1, offline, "1 个执行器应被判定离线");
        assertEquals(3, executorService.get(id).status(), "status 应置 3=离线");
        List<NotificationInfo> alerts = notificationsOf("EXECUTOR_OFFLINE");
        assertEquals(1, alerts.size(), "应生成 1 条离线告警");
        assertEquals(adminUserId, alerts.get(0).userId(), "告警接收人=系统管理员");
        assertTrue(alerts.get(0).content().contains("ex-offline-01"), "告警内容含执行器名");
        assertFalse(executorService.isOnline(id), "离线执行器不应参与 claim");
    }

    @Test
    void offline_重复检测_不重复告警_1小时合并() {
        long id = register("ex-offline-02");
        simulateHeartbeatStop(id);

        executorService.detectOfflineAndNotify();
        int second = executorService.detectOfflineAndNotify();

        assertEquals(0, second, "已离线执行器不应重复处理");
        assertEquals(1, notificationsOf("EXECUTOR_OFFLINE").size(),
                "IT-N3-01：同执行器 1 小时仅一条离线告警（dedup 合并）");
    }

    @Test
    void recover_恢复心跳_状态恢复但default_off通知不生成() {
        long id = register("ex-offline-03");
        simulateHeartbeatStop(id);
        executorService.detectOfflineAndNotify();

        executorService.heartbeat(id, 0);

        assertEquals(1, executorService.get(id).status(), "恢复心跳后 status 应回 1=在线");
        assertEquals(0, notificationsOf("EXECUTOR_ONLINE").size(),
                "P5 default_off：EXECUTOR_ONLINE 无偏好记录不生成");

        List<NotificationInfo> offline = notificationsOf("EXECUTOR_OFFLINE");
        assertEquals(1, offline.size());
        assertTrue(offline.get(0).dedupKey() != null
                        && !offline.get(0).dedupKey().equals("EXECUTOR_OFFLINE:" + id),
                "离线告警 dedup_key 应被标记已恢复，允许再次离线再告警");

        simulateHeartbeatStop(id);
        executorService.detectOfflineAndNotify();
        assertEquals(2, notificationsOf("EXECUTOR_OFFLINE").size(),
                "离线→恢复→再离线应产生第 2 条离线告警");
    }

    @Test
    void recover_defaultOff事件显式订阅后生成恢复通知() {
        long id = register("ex-offline-05");
        jdbcTemplate.update("INSERT INTO tp_notification_pref (user_id, event_type, enabled)"
                + " VALUES (?, 'EXECUTOR_ONLINE', 1)", adminUserId);

        simulateHeartbeatStop(id);
        executorService.detectOfflineAndNotify();
        executorService.heartbeat(id, 0);

        List<NotificationInfo> online = notificationsOf("EXECUTOR_ONLINE");
        assertEquals(1, online.size(), "显式订阅后恢复通知生成");
        assertEquals(adminUserId, online.get(0).userId(), "恢复通知接收人=系统管理员");
    }

    @Test
    void offline_阈值可配置_系统参数即时生效() {
        long id = register("ex-offline-04");
        ConfigInfo cfg = configService.create(1L, new ConfigInput(
                OFFLINE_SECONDS_KEY, "60", "系统", "int", 0, "执行器离线阈值（秒，N3）"));
        try {
            jdbcTemplate.update(
                    "UPDATE tp_executor SET last_heartbeat = now() - interval '40 seconds' WHERE id = ?",
                    id);
            int before = executorService.detectOfflineAndNotify();
            assertEquals(0, before, "阈值 60s 时 40s 未心跳不应判离线");
            assertEquals(1, executorService.get(id).status(), "应保持在线");

            // Threshold 30s keeps a 2x margin over the live dev-slot runner's 15s
            // heartbeat: the retired 10s value judged that foreign executor offline
            // too (count 2) - the old blanket TRUNCATE only masked this by wiping
            // its row. Aged 40s executor still crosses the 30s horizon (config
            // change takes effect immediately, no restart).
            configService.update(1L, cfg.id(), new ConfigInput(
                    OFFLINE_SECONDS_KEY, "30", "系统", "int", 0, "执行器离线阈值（秒，N3）"));
            int after = executorService.detectOfflineAndNotify();
            assertEquals(1, after, "阈值 30s 时 40s 未心跳应判离线");
            assertEquals(3, executorService.get(id).status());
        } finally {
            configService.delete(1L, cfg.id());
        }
    }
}
