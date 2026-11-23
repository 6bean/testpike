package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
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
class AppautoReservationConflictTest {

    @Autowired
    private AppautoReservationService reservationService;

    @Autowired
    private AppautoDeviceService deviceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;
    private long deviceId;
    private long holderUserId;

    @BeforeEach
    void setUp() {
        String code = "arsv" + System.nanoTime();
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "预约测试项目-" + code, code);
        projectId = jdbcTemplate.queryForObject("SELECT id FROM tp_project WHERE code = ?",
                Long.class, code);
        deviceId = deviceService.register(projectId, new AppautoDeviceInput(
                "预约机R", "RSV-A-" + projectId, "EMULATOR", null, "Pixel 8", "14",
                "1080x2400", 420, List.of("pool")));
        holderUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_user ORDER BY id LIMIT 1", Long.class);
    }

    @AfterEach
    void cleanAll() {
        jdbcTemplate.update("DELETE FROM tp_appauto_reservation WHERE device_id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_appauto_device WHERE id = ?", deviceId);
        jdbcTemplate.update("DELETE FROM tp_project WHERE id = ?", projectId);
    }

    private AppautoReservationInput input(long deviceIdArg, LocalDateTime starts,
                                          LocalDateTime ends, String reason) {
        return new AppautoReservationInput(projectId, deviceIdArg, null, holderUserId,
                starts, ends, reason);
    }

    @Test
    void create_landsActiveRowReadableBack() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView created = reservationService.create(
                input(deviceId, base, base.plusHours(2), "跑登录回归"));
        assertEquals("ACTIVE", created.state());
        assertNotNull(created.id());
        AppautoReservationView readBack = reservationService.get(created.id());
        assertEquals("跑登录回归", readBack.reason());
        assertEquals(base, readBack.startsAt());
        assertEquals(base.plusHours(2), readBack.endsAt());
    }

    @Test
    void overlappingWindow_rejectedWithHolderNameAndReleaseEstimate() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        reservationService.create(input(deviceId, base, base.plusHours(2), "首轮占用"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservationService.create(
                        input(deviceId, base.plusHours(1), base.plusHours(3), "想插一脚")));
        assertTrue(ex.getMessage().contains("已被"),
                "冲突拒文案应说明已被预约，实际=" + ex.getMessage());
        String holderName = jdbcTemplate.queryForObject(
                "SELECT COALESCE(NULLIF(display_name, ''), username) FROM tp_user WHERE id = ?",
                String.class, holderUserId);
        assertTrue(ex.getMessage().contains(holderName),
                "冲突拒应点名占用者，实际=" + ex.getMessage());
        assertTrue(ex.getMessage().contains("预计释放"),
                "冲突拒应给出释放预估，实际=" + ex.getMessage());
    }

    @Test
    void halfOpenBoundary_touchingWindowsAllowed() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        reservationService.create(input(deviceId, base, base.plusHours(2), "上午场"));
        AppautoReservationView second = reservationService.create(
                input(deviceId, base.plusHours(2), base.plusHours(4), "下午场"));
        assertEquals("ACTIVE", second.state());
    }

    @Test
    void terminalStateReservation_freesWindowForRebook() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView first = reservationService.create(
                input(deviceId, base, base.plusHours(2), "先用后撤"));
        reservationService.release(first.id(), "提前用完归还");
        AppautoReservationView again = reservationService.create(
                input(deviceId, base, base.plusHours(2), "释放后续约"));
        assertEquals("ACTIVE", again.state());
        reservationService.cancel(again.id(), "取消试验");
        AppautoReservationView third = reservationService.create(
                input(deviceId, base, base.plusHours(2), "取消后再约"));
        assertEquals("ACTIVE", third.state());
    }

    @Test
    void validationGates_invertedWindowAndUnknownDevice() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        ServiceException inverted = assertThrows(ServiceException.class,
                () -> reservationService.create(
                        input(deviceId, base.plusHours(2), base.plusHours(1), "倒置窗")));
        assertTrue(inverted.getMessage().contains("结束时间"),
                "倒置窗应人话说明结束须晚于开始，实际=" + inverted.getMessage());
        ServiceException ghost = assertThrows(ServiceException.class,
                () -> reservationService.create(
                        input(999999999L, base, base.plusHours(1), "幽灵设备")));
        assertTrue(ghost.getMessage().contains("设备"),
                "幽灵设备应人话点名设备，实际=" + ghost.getMessage());
    }

    @Test
    void stateMachine_transitionsAndGuards() {
        LocalDateTime base = LocalDateTime.now().plusDays(1).withNano(0);
        AppautoReservationView view = reservationService.create(
                input(deviceId, base, base.plusHours(2), "状态机演练"));
        AppautoReservationView released = reservationService.release(view.id(), "用毕主动归还");
        assertEquals("RELEASED", released.state());
        assertNotNull(released.releasedAt());
        assertEquals("用毕主动归还", released.releaseReason());
        ServiceException repeat = assertThrows(ServiceException.class,
                () -> reservationService.release(view.id(), "重复归还"));
        assertTrue(repeat.getMessage().contains("已是终态") || repeat.getMessage().contains("无法"),
                "终态重复释放应 409 人话，实际=" + repeat.getMessage());
        ServiceException ghostGet = assertThrows(ServiceException.class,
                () -> reservationService.get(999999999L));
        assertTrue(ghostGet.getMessage().contains("预约"),
                "读不存在的预约应人话点名，实际=" + ghostGet.getMessage());
    }
}
