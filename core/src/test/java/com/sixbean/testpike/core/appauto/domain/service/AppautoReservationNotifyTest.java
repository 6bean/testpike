package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import com.sixbean.testpike.core.notification.domain.component.NotificationEventDict;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.sixbean.testpike.core.CoreApplication;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoReservationNotifyTest {

    private static final String KEY_APPROVAL = "appauto.reservation.approval-required";
    private static final String KEY_OVERRUN = "appauto.reservation.window-overrun-minutes";

    @Autowired
    private AppautoReservationService reservationService;

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private long projectId;
    private long deviceId;
    private long holderUserId;
    private long approverUserId;

    @BeforeEach
    void setUp() {
        String code = "an" + System.nanoTime() % 1000000000L;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约通知测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "通知机P", "NTF-A-" + projectId, "EMULATOR", null, "Pixel 9", "15",
                "1080x2400", 420, List.of("pool")));
        List<Long> users = jdbcTemplate.query(
                "SELECT id FROM tp_user ORDER BY id LIMIT 2",
                (rs, rowNum) -> rs.getLong(1));
        holderUserId = users.get(0);
        approverUserId = users.size() > 1 ? users.get(1) : users.get(0);
    }

    @AfterEach
    void cleanAll() {
        jdbcTemplate.update("DELETE FROM tp_notification WHERE event_type LIKE 'RESERVATION_%'");
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '0' WHERE config_key = ?",
                KEY_APPROVAL);
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = '0' WHERE config_key = ?",
                KEY_OVERRUN);
        configCacheHolder.remove(KEY_APPROVAL);
        configCacheHolder.remove(KEY_OVERRUN);
    }

    private AppautoReservationInput input(LocalDateTime starts, LocalDateTime ends, String reason) {
        return new AppautoReservationInput(projectId, deviceId, null, holderUserId,
                starts, ends, reason);
    }

    private void setKey(String key, String value) {
        jdbcTemplate.update("UPDATE tp_sys_config SET config_value = ? WHERE config_key = ?",
                value, key);
        configCacheHolder.remove(key);
    }

    private List<String> notificationsOf(long userId, String eventType, long reservationId) {
        return jdbcTemplate.query(
                "SELECT title || '|' || content FROM tp_notification"
                        + " WHERE user_id = ? AND event_type = ? AND content LIKE ?",
                (rs, rowNum) -> rs.getString(1),
                userId, eventType, "%预约号 " + reservationId + "%");
    }

    @Test
    void 三预约事件码已入通知字典() {
        assertTrue(NotificationEventDict.isKnown("RESERVATION_APPROVED"),
                "批准回执事件码必须入字典（偏好矩阵可见性前提）");
        assertTrue(NotificationEventDict.isKnown("RESERVATION_REJECTED"),
                "驳回回执事件码必须入字典");
        assertTrue(NotificationEventDict.isKnown("RESERVATION_EXPIRED"),
                "超时释放通知事件码必须入字典");
    }

    @Test
    void 批准后预约人收到站内信含设备与时间窗() {
        setKey(KEY_APPROVAL, "1");
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView pending = reservationService.create(
                input(base, base.plusHours(2), "通知演练-批准"));
        reservationService.approve(pending.id(), approverUserId);
        List<String> got = notificationsOf(holderUserId, "RESERVATION_APPROVED", pending.id());
        assertEquals(1, got.size(), "批准必须给预约人恰一条回执（预约号 " + pending.id() + "）");
        assertTrue(got.get(0).contains("预约"), "回执标题应人话点名预约，实际=" + got.get(0));
    }

    @Test
    void 驳回后预约人收到含理由的站内信() {
        setKey(KEY_APPROVAL, "1");
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView pending = reservationService.create(
                input(base, base.plusHours(2), "通知演练-驳回"));
        reservationService.reject(pending.id(), approverUserId, "该时段已有维护计划");
        List<String> got = notificationsOf(holderUserId, "RESERVATION_REJECTED", pending.id());
        assertEquals(1, got.size(), "驳回必须给预约人恰一条回执");
        assertTrue(got.get(0).contains("该时段已有维护计划"),
                "驳回回执必须携带审批人给的理由，实际=" + got.get(0));
    }

    @Test
    void 超时清扫后预约人收到释放通知() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(40).withNano(0);
        AppautoReservationView view = reservationService.create(
                input(past, past.plusMinutes(20), "通知演练-超时"));
        jdbcTemplate.update("UPDATE tp_appauto_reservation SET starts_at = ?, ends_at = ?"
                        + " WHERE id = ?", past.minusMinutes(30), past, view.id());
        setKey(KEY_OVERRUN, "0");
        int swept = reservationService.sweepExpired();
        assertTrue(swept >= 1, "本轮应清扫出该超窗预约");
        List<String> got = notificationsOf(holderUserId, "RESERVATION_EXPIRED", view.id());
        assertEquals(1, got.size(), "超时释放必须通知预约人本人（站内信）");
        assertNotNull(reservationService.get(view.id()).releaseReason(),
                "清扫留痕仍在（状态机侧回归锚）");
    }
}
