package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.admin.appauto.api.dto.TakeoverCreateRequest;
import com.sixbean.testpike.admin.appauto.api.dto.TakeoverInputEnqueueRequest;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoTakeoverBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoTakeoverController takeover =
            new AppautoTakeoverController(client, guard);
    private final AppautoDeviceController devices = new AppautoDeviceController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private AppautoTakeoverView row(long id, long projectId, String state) {
        return new AppautoTakeoverView(id, 9L, projectId, 123L, 7L, 21L, state,
                "联调需要直接操作", 21L, LocalDateTime.of(2026, 10, 8, 4, 0),
                LocalDateTime.of(2026, 10, 8, 4, 30), null,
                LocalDateTime.of(2026, 10, 8, 3, 50));
    }


    @Test
    void 发起接管_申请人服务端注入_查看闸按设备回显归属前置() {
        login(7L);
        when(client.findDevice(9L)).thenReturn(device(9L, 7L, "ANDROID"));
        when(client.requestTakeover(eq(9L), eq(123L), anyString(), eq(7L)))
                .thenReturn(row(5L, 7L, "pending"));

        AppautoTakeoverView got = takeover.request(new TakeoverCreateRequest(9L, 123L,
                "联调需要直接操作")).getData();

        assertEquals("pending", got.state());
        assertEquals(21L, got.occupierId(), "占用者=派发人快照由 core 解析回显，前端传不了");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).requestTakeover(9L, 123L, "联调需要直接操作", 7L);
    }

    @Test
    void 发起接管_跨项目设备拒_零申请副作用() {
        login(7L);
        when(client.findDevice(9L)).thenReturn(device(9L, 8L, "ANDROID"));
        doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(8L));
        assertThrows(ServiceException.class,
                () -> takeover.request(new TakeoverCreateRequest(9L, null, "x")));
        verify(client, never()).requestTakeover(anyLong(), any(), any(), anyLong());
    }

    @Test
    void 发起接管_空闲设备core人话409原样上抛() {
        login(7L);
        when(client.findDevice(9L)).thenReturn(device(9L, 7L, "ANDROID"));
        when(client.requestTakeover(eq(9L), any(), any(), eq(7L))).thenThrow(
                new ServiceException("该设备当前没有进行中的占用会话（空闲）——无需接管，可直接对设备发起监看或试运行",
                        BizErrorCode.RESOURCE_CONFLICT));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeover.request(new TakeoverCreateRequest(9L, null, "x")));
        assertTrue(ex.getMessage().contains("无需接管"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 发起接管_共享位设备无项目归属_不落项目闸仍可接管() {
        login(7L);
        when(client.findDevice(9L)).thenReturn(device(9L, null, "ANDROID"));
        when(client.requestTakeover(eq(9L), any(), any(), eq(7L))).thenReturn(row(5L, 0L, "pending"));
        assertEquals("pending", takeover.request(new TakeoverCreateRequest(9L, null, "x"))
                .getData().state());
        verify(guard, never()).assertCanView(any(), anyLong());
    }


    @Test
    void 同意_判定人服务端注入_查看闸按回显归属() {
        login(21L);
        when(client.findTakeover(5L)).thenReturn(row(5L, 7L, "pending"));
        when(client.agreeTakeover(5L, 21L)).thenReturn(row(5L, 7L, "active"));
        assertEquals("active", takeover.agree(5L).getData().state(),
                "agreed 即生效单事务由 core 回显");
        verify(guard).assertCanView(any(), eq(7L));
        verify(client).agreeTakeover(5L, 21L);
    }

    @Test
    void 驳回_非占用者core越权拒原样上抛_判定人仍服务端注入() {
        login(99L);
        when(client.findTakeover(5L)).thenReturn(row(5L, 7L, "pending"));
        when(client.rejectTakeover(5L, 99L)).thenThrow(new ServiceException(
                "仅占用者本人可驳回该接管申请（占用者为派发人快照，判定权不可转让）",
                BizErrorCode.FORBIDDEN));
        ServiceException ex = assertThrows(ServiceException.class, () -> takeover.reject(5L));
        assertTrue(ex.getMessage().contains("仅占用者本人"));
        verify(client).rejectTakeover(5L, 99L);
    }


    @Test
    void 强接_系统管理员专属闸_非管理员拒且零core副作用() {
        login(7L);
        doThrow(new ServiceException("仅系统管理员可强制接管设备会话", BizErrorCode.FORBIDDEN))
                .when(guard).assertSystemAdminOnly(any(), anyString());
        ServiceException ex = assertThrows(ServiceException.class, () -> takeover.force(5L));
        assertTrue(ex.getMessage().contains("系统管理员"));
        verify(client, never()).forceTakeover(anyLong(), anyLong());
    }

    @Test
    void 强接_管理员身份服务端注入_forced态由core回显() {
        login(3L);
        when(client.forceTakeover(5L, 3L)).thenReturn(row(5L, 7L, "forced"));
        assertEquals("forced", takeover.force(5L).getData().state());
        verify(client).forceTakeover(5L, 3L);
    }


    @Test
    void 结束_操作人透传_残留指令清空由core单点保证() {
        login(7L);
        when(client.findTakeover(5L)).thenReturn(row(5L, 7L, "active"));
        when(client.endTakeover(5L, 7L)).thenReturn(row(5L, 7L, "ended"));
        assertEquals("ended", takeover.end(5L).getData().state());
        verify(guard).assertCanView(any(), eq(7L));
    }

    @Test
    void 单读_视图字段完整不截断_留痕位可空诚实() {
        login(7L);
        when(client.findTakeover(5L)).thenReturn(row(5L, 7L, "active"));
        AppautoTakeoverView got = takeover.get(5L).getData();
        assertEquals("联调需要直接操作", got.reason(), "申请事由人话不截断（弹窗明细消费）");
        assertEquals(LocalDateTime.of(2026, 10, 8, 4, 30), got.expiresAt(), "有效窗必须透出（前端倒计时）");
        org.junit.jupiter.api.Assertions.assertNull(got.recordingRef(),
                "留痕位包②收口前恒空=诚实占位禁假造");
    }

    @Test
    void 单读_异项目拒() {
        login(7L);
        when(client.findTakeover(5L)).thenReturn(row(5L, 8L, "active"));
        doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(8L));
        assertThrows(ServiceException.class, () -> takeover.get(5L));
    }


    @Test
    void 入队_指令不透明透传_操作人服务端注入_高频口零前置回显() {
        login(7L);
        List<Map<String, Object>> commands = List.of(
                Map.of("action", "tap", "x", 5000, "y", 8000, "platform", "ANDROID"),
                Map.of("action", "text", "value", "你好"));
        when(client.enqueueTakeoverInput(eq(5L), eq(7L), eq(commands)))
                .thenReturn(Map.of("accepted", 2));
        Map<String, Object> got = takeover.enqueueInput(5L,
                new TakeoverInputEnqueueRequest(commands)).getData();
        assertEquals(2, got.get("accepted"));
        verify(client, never()).findTakeover(5L);
        verify(guard, never()).assertCanView(any(), anyLong());
    }

    @Test
    void 入队_超容量core整批拒人话原样上抛() {
        login(7L);
        List<Map<String, Object>> commands = List.of(Map.of("action", "tap"));
        when(client.enqueueTakeoverInput(eq(5L), eq(7L), eq(commands))).thenThrow(
                new ServiceException("接管指令队列已满（上限内整批拒绝，不做部分入账）——请等待画面响应后再发",
                        BizErrorCode.RESOURCE_CONFLICT));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> takeover.enqueueInput(5L, new TakeoverInputEnqueueRequest(commands)));
        assertTrue(ex.getMessage().contains("整批拒绝"));
    }


    @Test
    void 设备档案视图_platform字段桥面不丢_接管置灰判据消费() {
        AppautoDeviceView view = device(9L, 7L, "MP");
        assertEquals("MP", view.platform(),
                "平台码=接管操作置灰判据与指令 platform 来源单点（V206 镜像，禁前端猜）");
    }

    @Test
    void 设备保存入参_platform尾追透传() {
        login(7L);
        com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest req =
                new com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest(
                        7L, "新设备", "S-1", "ADB", "10.0.0.9:5555",
                        null, null, null, null, List.of(), "ANDROID");
        when(client.registerDevice(req)).thenReturn(9L);
        assertEquals(9L, devices.register(req).getData());
        assertEquals("ANDROID", req.platform());
        verify(client).registerDevice(req);
    }

    private AppautoDeviceView device(long id, Long projectId, String platform) {
        return new AppautoDeviceView(id, projectId, "Pixel-6", "emulator-5554", "EMULATOR",
                null, "Pixel 6", "13", "1080x2400", 420, List.of("smoke"),
                "GREEN", 0, LocalDateTime.now(), null, null, null, platform);
    }
}
