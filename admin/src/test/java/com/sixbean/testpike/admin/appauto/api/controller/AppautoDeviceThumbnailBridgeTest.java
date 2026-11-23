package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoDeviceView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoDeviceThumbnailBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoDeviceController devices = new AppautoDeviceController(client, guard);

    @AfterEach
    void clearUser() {
        UserContext.clear();
    }

    private void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u", "名", "部", List.of("TESTER"), 0L, null));
    }

    private static AppautoDeviceView device(Long projectId) {
        return new AppautoDeviceView(9L, projectId, "em-1", "SN-9", "EMULATOR",
                "10.0.0.9:5555", "Pixel-6", "13", "1080x2400", 420, List.of("wall"),
                "ONLINE", 0, null, null, null, null, "ANDROID");
    }

    @Test
    void 项目设备_查看闸按档案归属前置_命中后才取出图() {
        login(3L);
        when(client.findDevice(9L)).thenReturn(device(5L));
        AppautoDeviceThumbnailView live = new AppautoDeviceThumbnailView(
                9L, 42L, "LIVE", null, "aGVsbG8=", 360, 800, 1080, 2400);
        when(client.deviceThumbnail(9L)).thenReturn(live);
        AppautoDeviceThumbnailView got = devices.thumbnail(9L).getData();
        verify(guard).assertCanView(any(CurrentUser.class), eq(5L));
        assertEquals("aGVsbG8=", got.imageBase64());
        assertEquals(42L, got.taskId());
        assertEquals(2400, got.sourceHeight());
    }

    @Test
    void 异项目越权_assertCanView拒后取图零调用() {
        login(3L);
        when(client.findDevice(9L)).thenReturn(device(5L));
        doThrowView();
        assertThrows(ServiceException.class, () -> devices.thumbnail(9L));
        verify(client, never()).deviceThumbnail(anyLong());
    }

    private void doThrowView() {
        org.mockito.Mockito.doThrow(new ServiceException("无权查看该项目", BizErrorCode.FORBIDDEN))
                .when(guard).assertCanView(any(), eq(5L));
    }

    @Test
    void 共享位设备_归属空不落项目闸_照常出图() {
        login(3L);
        when(client.findDevice(9L)).thenReturn(device(null));
        when(client.deviceThumbnail(9L)).thenReturn(
                AppautoDeviceThumbnailView.withoutFrame(9L, null, "IDLE", "没有运行中的任务"));
        AppautoDeviceThumbnailView got = devices.thumbnail(9L).getData();
        verify(guard, never()).assertCanView(any(), anyLong());
        assertEquals("IDLE", got.state());
        assertEquals("没有运行中的任务", got.reason());
    }

    @Test
    void core人话404_原样上抛_不吞不加工() {
        login(3L);
        when(client.findDevice(9L)).thenReturn(device(5L));
        when(client.deviceThumbnail(9L))
                .thenThrow(new ServiceException("设备不存在", BizErrorCode.NOT_FOUND));
        ServiceException thrown = assertThrows(ServiceException.class, () -> devices.thumbnail(9L));
        assertEquals("设备不存在", thrown.getMessage());
    }
}
