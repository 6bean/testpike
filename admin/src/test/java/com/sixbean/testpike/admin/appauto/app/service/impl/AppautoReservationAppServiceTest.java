package com.sixbean.testpike.admin.appauto.app.service.impl;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarLane;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class AppautoReservationAppServiceTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final AppautoReservationAppServiceImpl appService =
            new AppautoReservationAppServiceImpl(client);

    private static final LocalDateTime FROM = LocalDateTime.of(2026, 10, 10, 9, 0);
    private static final LocalDateTime TO = LocalDateTime.of(2026, 10, 11, 9, 0);

    private AppautoDeviceView device(long id, String name, String model) {
        return new AppautoDeviceView(id, 3L, name, "SN-" + id, "EMULATOR", null,
                model, "13", "1080x2400", 420, List.of("pool"), "HEALTHY", 0,
                null, null, null, null);
    }

    private AppautoReservationView reservation(long id, long deviceId, String state,
                                               LocalDateTime starts, LocalDateTime ends) {
        return new AppautoReservationView(id, 3L, deviceId, null, 7L, "张三",
                starts, ends, state, "日历演练", null, null, null, null, FROM);
    }

    private AppautoLeaseRunView lease(long id, long deviceId, String state,
                                      LocalDateTime acquiredAt) {
        return new AppautoLeaseRunView(id, deviceId, 3L, 66L, "exec-1", state,
                acquiredAt, acquiredAt.plusMinutes(30), null, null);
    }

    private void stubDevices(AppautoDeviceView... devices) {
        when(client.listDevices(eq(3L), isNull())).thenReturn(List.of(devices));
    }

    @Test
    void calendar_projectsReservationsIntoLanes_andDropsOutOfWindowOnes() {
        stubDevices(device(11L, "机一", "Pixel 7"), device(12L, "机二", "iPhone 14"));
        when(client.listReservations(isNull(), eq(3L), isNull(), eq(false))).thenReturn(List.of(
                reservation(101L, 11L, "ACTIVE", FROM.plusHours(2), FROM.plusHours(4)),
                reservation(102L, 11L, "ACTIVE", FROM.minusHours(2), FROM),
                reservation(103L, 12L, "ACTIVE", TO.plusHours(1), TO.plusHours(2)),
                reservation(104L, 12L, "PENDING_APPROVAL", TO.plusHours(3), TO.plusHours(4))));
        when(client.listDeviceLeaseRuns(11L)).thenReturn(List.of(
                lease(900L, 11L, "RELEASED", FROM.minusHours(3)),
                lease(901L, 11L, "ACTIVE", FROM.plusHours(1))));
        when(client.listDeviceLeaseRuns(12L)).thenReturn(List.of());

        ReservationCalendarView view = appService.calendar(3L, FROM, TO);

        assertEquals(2, view.lanes().size(), "泳道骨=项目设备清单，无预约设备也成道");
        ReservationCalendarLane lane1 = view.lanes().get(0);
        assertEquals(1, lane1.reservations().size(), "机一只应剩窗内 1 条预约");
        assertEquals(101L, lane1.reservations().get(0).id(), "落道行须是窗内那条");
        assertEquals(901L, lane1.activeLease().id(), "在跑租约须拼装进占用条位");
        ReservationCalendarLane lane2 = view.lanes().get(1);
        assertTrue(lane2.reservations().isEmpty(), "窗外预约须被剔除");
        assertNull(lane2.activeLease(), "无 ACTIVE 租约=占用条空位");
        assertEquals(1, view.pendingApprovalCount(), "角标须含窗外待审批预约");
        assertEquals(FROM, view.from());
        assertEquals(TO, view.to());
    }

    @Test
    void calendar_overlappingTailAndHeadEdges_included() {
        stubDevices(device(11L, "机一", "Pixel 7"));
        when(client.listReservations(isNull(), eq(3L), isNull(), eq(false))).thenReturn(List.of(
                reservation(201L, 11L, "ACTIVE", TO, TO.plusHours(1)),
                reservation(202L, 11L, "ACTIVE", FROM.minusHours(1), FROM.plusHours(1))));
        when(client.listDeviceLeaseRuns(11L)).thenReturn(List.of());

        ReservationCalendarView view = appService.calendar(3L, FROM, TO);

        assertEquals(1, view.lanes().get(0).reservations().size(),
                "窗起恰贴 to 须剔、跨窗头须留（半开重叠口径）");
        assertEquals(202L, view.lanes().get(0).reservations().get(0).id());
    }
}
