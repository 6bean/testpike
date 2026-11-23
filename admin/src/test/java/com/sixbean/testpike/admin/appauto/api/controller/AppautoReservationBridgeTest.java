package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCreateRequest;
import com.sixbean.testpike.admin.appauto.app.service.AppautoReservationAppService;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class AppautoReservationBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoReservationAppService appService =
            mock(AppautoReservationAppService.class);
    private final AppautoReservationController reservation =
            new AppautoReservationController(client, guard, appService);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private ReservationCreateRequest request(Long projectId) {
        return new ReservationCreateRequest(projectId, 9L, null,
                LocalDateTime.of(2026, 10, 11, 10, 0),
                LocalDateTime.of(2026, 10, 11, 12, 0), "跑登录回归");
    }

    private AppautoReservationView view(long id, Long projectId, String state) {
        return new AppautoReservationView(id, projectId, 9L, null, 7L, "张三",
                LocalDateTime.of(2026, 10, 11, 10, 0),
                LocalDateTime.of(2026, 10, 11, 12, 0), state, "跑登录回归",
                null, null, null, null,
                LocalDateTime.of(2026, 10, 10, 8, 0));
    }


    @Test
    void 创建_预约人服务端注入_项目查看闸前置() {
        login(7L);
        when(client.createReservation(any(), eq(7L))).thenReturn(view(5L, 3L, "ACTIVE"));

        AppautoReservationView got = reservation.create(request(3L)).getData();

        assertEquals("ACTIVE", got.state());
        verify(client).createReservation(any(ReservationCreateRequest.class), eq(7L));
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void 创建_共享位预约_不落项目闸零副作用() {
        login(7L);
        when(client.createReservation(any(), eq(7L))).thenReturn(view(6L, null, "ACTIVE"));

        reservation.create(request(null));

        verify(guard, never()).assertCanView(any(), anyLong());
    }

    @Test
    void 创建_core冲突人话原样上抛_零加工() {
        login(7L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "ACTIVE"));
        doThrow(new ServiceException("该设备在 10:00–12:00 时段已被 张三 预约（预约号 5），"
                + "预计释放时间 12:00——请选择其他时段或其他设备",
                BizErrorCode.RESOURCE_CONFLICT))
                .when(client).createReservation(any(), eq(7L));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservation.create(request(3L)));
        assertEquals("该设备在 10:00–12:00 时段已被 张三 预约（预约号 5），预计释放时间 12:00"
                + "——请选择其他时段或其他设备", ex.getMessage(), "core 文案禁 admin 改写");
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode().intValue());
    }


    @Test
    void 列表_未选项目_人话拒零外呼() {
        login(7L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservation.list(9L, null, null, false));
        assertEquals(true, ex.getMessage().contains("项目"), "实际=" + ex.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void 列表_项目查看闸_参数恒等透传() {
        login(7L);
        when(client.listReservations(9L, 3L, null, true)).thenReturn(List.of(view(5L, 3L, "ACTIVE")));

        List<AppautoReservationView> got = reservation.list(9L, 3L, null, true).getData();

        assertEquals(1, got.size());
        verify(client).listReservations(9L, 3L, null, true);
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void 列表_批次反向过滤参数透传() {
        login(7L);
        when(client.listReservations(null, 3L, 66L, false)).thenReturn(List.of(view(5L, 3L, "ACTIVE")));

        List<AppautoReservationView> got = reservation.list(null, 3L, 66L, false).getData();

        assertEquals(1, got.size());
        verify(client).listReservations(null, 3L, 66L, false);
    }


    @Test
    void 绑定批次_写闸按回显归属_batchId原样透传() {
        login(7L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "ACTIVE"));
        when(client.bindReservationBatch(5L, 66L)).thenReturn(view(5L, 3L, "ACTIVE"));

        reservation.bindBatch(5L, 66L);

        verify(client).bindReservationBatch(5L, 66L);
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void 绑定批次_解绑空参数透传_跨项目人话拒原样上抛() {
        login(7L);
        when(client.findReservation(6L)).thenReturn(view(6L, null, "ACTIVE"));
        when(client.bindReservationBatch(6L, null)).thenReturn(view(6L, null, "ACTIVE"));

        reservation.bindBatch(6L, null);

        verify(client).bindReservationBatch(6L, null);
        verify(guard, never()).assertCanView(any(), anyLong());

        doThrow(new ServiceException("批次与预约不属于同一项目", BizErrorCode.RESOURCE_CONFLICT))
                .when(client).bindReservationBatch(5L, 66L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "ACTIVE"));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservation.bindBatch(5L, 66L));
        assertTrue(ex.getMessage().contains("同一项目"),
                "core 冲突文案须原样上抛零加工，实际=" + ex.getMessage());
    }

    @Test
    void 日历_未选项目人话拒零外呼_委托应用服务() {
        login(7L);
        LocalDateTime from = LocalDateTime.of(2026, 10, 10, 9, 0);
        LocalDateTime to = LocalDateTime.of(2026, 10, 11, 9, 0);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> reservation.calendar(null, from, to));
        assertTrue(ex.getMessage().contains("项目"), "应人话提示先选项目");
        verifyNoInteractions(appService);

        ReservationCalendarView view = new ReservationCalendarView(List.of(), 0, from, to);
        when(appService.calendar(3L, from, to)).thenReturn(view);
        assertEquals(view, reservation.calendar(3L, from, to).getData());
        verify(guard).assertCanView(any(), eq(3L));
        verify(appService).calendar(3L, from, to);
    }


    @Test
    void 归还_按回显归属落查看闸_视图透传() {
        login(7L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "ACTIVE"));
        when(client.releaseReservation(5L, "用完了")).thenReturn(view(5L, 3L, "RELEASED"));

        AppautoReservationView got = reservation.release(5L, "用完了").getData();

        assertEquals("RELEASED", got.state());
        verify(client).releaseReservation(5L, "用完了");
        verify(guard).assertCanView(any(), eq(3L));
    }

    @Test
    void 取消_共享位回显不落项目闸() {
        login(7L);
        when(client.findReservation(6L)).thenReturn(view(6L, null, "ACTIVE"));
        when(client.cancelReservation(6L, null)).thenReturn(view(6L, null, "CANCELLED"));

        reservation.cancel(6L, null);

        verify(guard, never()).assertCanView(any(), anyLong());
    }


    @Test
    void 批准_审批人服务端注入_回显归属落管理闸() {
        login(8L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "PENDING_APPROVAL"));
        when(client.approveReservation(5L, 8L)).thenReturn(view(5L, 3L, "ACTIVE"));

        AppautoReservationView got = reservation.approve(5L).getData();

        assertEquals("ACTIVE", got.state());
        verify(client).approveReservation(5L, 8L);
        verify(guard).assertCanManage(any(), eq(3L));
    }

    @Test
    void 驳回_理由与审批人一并透传() {
        login(8L);
        when(client.findReservation(5L)).thenReturn(view(5L, 3L, "PENDING_APPROVAL"));
        when(client.rejectReservation(5L, 8L, "时段冲突")).thenReturn(view(5L, 3L, "REJECTED"));

        reservation.reject(5L, "时段冲突");

        verify(client).rejectReservation(5L, 8L, "时段冲突");
    }

    @Test
    void 清扫_运维口计数原样透传() {
        login(8L);
        when(client.sweepReservations()).thenReturn(3);

        assertEquals(3, reservation.sweep().getData().intValue());
        verify(client).sweepReservations();
    }
}
