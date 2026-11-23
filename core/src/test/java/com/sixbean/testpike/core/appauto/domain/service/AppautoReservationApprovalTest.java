package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class AppautoReservationApprovalTest {

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
        String code = "arap" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约审批测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "预约机P", "APV-A-" + projectId, "EMULATOR", null, "Pixel 9", "15",
                "1080x2400", 420, List.of("pool")));
        List<Long> users = jdbcTemplate.query(
                "SELECT id FROM tp_user ORDER BY id LIMIT 2",
                (rs, rowNum) -> rs.getLong(1));
        holderUserId = users.get(0);
        approverUserId = users.size() > 1 ? users.get(1) : users.get(0);
    }

    @AfterEach
    void cleanAll() {
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

    private void backdateWindow(long reservationId, LocalDateTime starts, LocalDateTime ends) {
        jdbcTemplate.update("UPDATE tp_appauto_reservation SET starts_at = ?, ends_at = ?"
                        + " WHERE id = ?", starts, ends, reservationId);
    }

    @Test
    void seeds_presentWithDefaultsOff() {
        String approval = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, KEY_APPROVAL);
        assertEquals("0", approval, "V216 审批开关必须落库且默认关（直通）");
        String overrun = jdbcTemplate.queryForObject(
                "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                String.class, KEY_OVERRUN);
        assertEquals("0", overrun, "V216 宽限分钟必须落库且默认 0");
    }

    @Test
    void switchOff_createFlipsStraightToActive() {
        setKey(KEY_APPROVAL, "0");
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView created = reservationService.create(
                input(base, base.plusHours(2), "直通演练"));
        assertEquals("ACTIVE", created.state(), "开关关=直通落 ACTIVE");
    }

    @Test
    void switchOn_createLandsPendingThenApproveFlipsActiveWithTrace() {
        setKey(KEY_APPROVAL, "1");
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView pending = reservationService.create(
                input(base, base.plusHours(2), "审批演练"));
        assertEquals("PENDING_APPROVAL", pending.state(), "开关开=创建落待审批");
        assertThrows(ServiceException.class, () -> reservationService.create(
                input(base.plusMinutes(30), base.plusHours(3), "想插一脚")));
        AppautoReservationView approved = reservationService.approve(pending.id(), approverUserId);
        assertEquals("ACTIVE", approved.state(), "批准口必须翻 ACTIVE");
        assertEquals(approverUserId, approved.approvedBy(), "批准必须留审批人痕");
        assertNotNull(approved.approvedAt(), "批准必须留审批时间痕");
    }

    @Test
    void reject_landsRejectedWithReasonAndTerminalGuards() {
        setKey(KEY_APPROVAL, "1");
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView pending = reservationService.create(
                input(base, base.plusHours(2), "驳回演练"));
        AppautoReservationView rejected = reservationService.reject(
                pending.id(), approverUserId, "该时段已有维护计划");
        assertEquals("REJECTED", rejected.state());
        assertEquals("该时段已有维护计划", rejected.releaseReason(), "驳回理由必须留痕可读回");
        ServiceException repeat = assertThrows(ServiceException.class,
                () -> reservationService.approve(pending.id(), approverUserId));
        assertTrue(repeat.getMessage().contains("待审批"),
                "终态重复批准应 409 人话点名态闸，实际=" + repeat.getMessage());
        ServiceException ghost = assertThrows(ServiceException.class,
                () -> reservationService.reject(999999999L, approverUserId, "幽灵单"));
        assertTrue(ghost.getMessage().contains("预约"),
                "幽灵号驳回应人话点名预约，实际=" + ghost.getMessage());
    }

    @Test
    void sweep_expiresActivePastOverrunAndKeepsGrace() {
        LocalDateTime past = LocalDateTime.now().minusMinutes(10).withNano(0);
        AppautoReservationView view = reservationService.create(
                input(past, past.plusHours(1), "超窗清扫演练"));
        backdateWindow(view.id(), past.minusMinutes(50), past);
        setKey(KEY_OVERRUN, "30");
        reservationService.sweepExpired();
        assertEquals("ACTIVE", reservationService.get(view.id()).state(),
                "宽限内的超窗预约不应被释放");
        setKey(KEY_OVERRUN, "0");
        int swept = reservationService.sweepExpired();
        assertTrue(swept >= 1, "本轮至少清扫出该预约");
        AppautoReservationView expired = reservationService.get(view.id());
        assertEquals("EXPIRED", expired.state(), "超窗过宽限必须自动释放为已超时释放");
        assertNotNull(expired.releaseReason(), "自动释放必须留痕（release_reason）");
    }

    @Test
    void sweep_expiresUnapprovedPendingPastWindowWithoutGrace() {
        setKey(KEY_APPROVAL, "1");
        LocalDateTime past = LocalDateTime.now().minusMinutes(30).withNano(0);
        AppautoReservationView pending = reservationService.create(
                input(past, past.plusHours(1), "待审超窗演练"));
        backdateWindow(pending.id(), past.minusMinutes(60), past);
        setKey(KEY_OVERRUN, "30");
        reservationService.sweepExpired();
        assertEquals("EXPIRED", reservationService.get(pending.id()).state(),
                "待审批超窗未审=无宽限直接释放（宽限键只管生效中的收尾缓冲）");
    }
}
