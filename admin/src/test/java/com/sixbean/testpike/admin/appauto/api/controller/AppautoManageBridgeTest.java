package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoLeaseRunView;
import com.sixbean.testpike.admin.appauto.api.dto.EngineManageRequest;
import com.sixbean.testpike.admin.appauto.api.dto.PlatformManageRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoManageBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard guard =
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class);
    private final AppautoCatalogController catalog = new AppautoCatalogController(client, guard);
    private final AppautoDeviceController devices = new AppautoDeviceController(client, guard);

    private AppautoLeaseRunView run(long id, String state) {
        return new AppautoLeaseRunView(id, 9L, 7L, 123L, "exec-01", state,
                LocalDateTime.of(2026, 10, 2, 9, 0), LocalDateTime.of(2026, 10, 2, 9, 30),
                "ACTIVE".equals(state) ? null : LocalDateTime.of(2026, 10, 2, 9, 20),
                "ACTIVE".equals(state) ? null : "批次完成归还");
    }

    @Test
    void 引擎写口_按code透传_core人话404与值域文案原样上抛() {
        EngineManageRequest request = new EngineManageRequest("Mobilewright 主力", 5, "https://d.example/mw", 1);
        catalog.updateEngine("MOBILEWRIGHT", request);
        verify(client).updateEngine(eq("MOBILEWRIGHT"), eq(request));

        doThrow(new ServiceException("引擎描述符「NOPE」不存在——请从引擎目录选择有效引擎编码",
                BizErrorCode.NOT_FOUND)).when(client).updateEngine(eq("NOPE"), eq(request));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> catalog.updateEngine("NOPE", request));
        assertTrue(ex.getMessage().contains("引擎目录选择"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 平台写口_按code透传_下架文案原样() {
        PlatformManageRequest request = new PlatformManageRequest("预留位说明", 0);
        catalog.updatePlatform("WEB_MOBILE", request);
        verify(client).updatePlatform(eq("WEB_MOBILE"), eq(request));

        doThrow(new ServiceException("平台状态仅支持 1=启用/0=预留停用，收到 7",
                BizErrorCode.VALIDATION_ERROR)).when(client).updatePlatform(eq("WEB_MOBILE"), eq(request));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> catalog.updatePlatform("WEB_MOBILE", request));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void 设备占用流水_归属闸按core回显_视图字段完整透传() {
        com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView device =
                new com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView(
                        9L, 7L, "Pixel-6", "emulator-5554", "EMULATOR", null,
                        "Pixel 6", "13", "1080x2400", 420, List.of("smoke"),
                        "GREEN", 0, LocalDateTime.now(), LocalDateTime.now(), null, null);
        when(client.findDevice(9L)).thenReturn(device);
        when(client.listDeviceLeaseRuns(9L)).thenReturn(List.of(run(2L, "RELEASED"), run(1L, "ACTIVE")));

        List<AppautoLeaseRunView> runs = devices.leaseRuns(9L).getData();
        assertEquals(2, runs.size());
        AppautoLeaseRunView active = runs.get(1);
        assertEquals("exec-01", active.holderExecutorId(), "占用者执行器标识不得丢（设备墙排队文案消费）");
        assertEquals("批次完成归还", runs.get(0).releaseReason(), "归因人话不截断");
        verify(guard).assertCanView(com.sixbean.testpike.admin.auth.app.component.UserContext.current(), 7L);
    }

    @Test
    void 设备不存在时流水口_core人话404透传不落归属闸() {
        when(client.findDevice(999L)).thenThrow(new ServiceException(
                "设备档案不存在（可能已删除）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> devices.leaseRuns(999L));
        assertTrue(ex.getMessage().contains("设备档案不存在"), "先回显判权链路=core 404 原样透出");
    }


    private com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView sharedDevice(long id) {
        return new com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView(
                id, null, "实验室公共机-Pad", "SHARED-PAD-01", "ADB", "10.0.0.9:5555",
                "Pixel Pad", "14", "2560x1600", 320, List.of("lab"),
                "GREEN", 0, LocalDateTime.now(), LocalDateTime.now(), null, null);
    }

    private com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest saveReq(Long projectId) {
        return new com.sixbean.testpike.admin.appauto.api.dto.DeviceSaveRequest(
                projectId, "新设备", "NEW-SERIAL", "EMULATOR", null,
                null, null, null, null, List.of());
    }

    @Test
    void 注册共享位档案_无项目归属_走系统管理员专属闸不拆箱NPE() {
        doThrow(new ServiceException("仅系统管理员可登记跨项目共享设备", BizErrorCode.FORBIDDEN))
                .when(guard).assertSystemAdminOnly(
                        org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        ServiceException ex = assertThrows(ServiceException.class, () -> devices.register(saveReq(null)));
        assertTrue(ex.getMessage().contains("系统管理员"), "共享位写入闸人话文案");
        verify(client, org.mockito.Mockito.never()).registerDevice(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void 注册项目内档案_仍走项目管理闸_不触共享位闸() {
        devices.register(saveReq(7L));
        verify(guard).assertCanManage(org.mockito.ArgumentMatchers.any(), eq(7L));
        verify(guard, org.mockito.Mockito.never()).assertSystemAdminOnly(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void 编辑删除解隔离共享位档案_按core回显空归属转系统管理员闸() {
        when(client.findDevice(9L)).thenReturn(sharedDevice(9L));
        devices.update(9L, saveReq(null));
        devices.unquarantine(9L);
        devices.delete(9L);
        verify(guard, org.mockito.Mockito.times(3)).assertSystemAdminOnly(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyString());
        verify(guard, org.mockito.Mockito.never()).assertCanManage(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void 详情与占用流水共享位档案_全员可见不落项目闸不NPE() {
        when(client.findDevice(9L)).thenReturn(sharedDevice(9L));
        when(client.listDeviceLeaseRuns(9L)).thenReturn(List.of(run(2L, "RELEASED")));
        var view = devices.get(9L).getData();
        assertEquals("实验室公共机-Pad", view.name(), "共享位详情直读，归属为空是合法态");
        assertEquals(1, devices.leaseRuns(9L).getData().size());
        verify(guard, org.mockito.Mockito.never()).assertCanView(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
