package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoAppView;
import com.sixbean.testpike.admin.appauto.api.dto.AppPackageSaveRequest;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoPackageBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final AppautoPackageController controller = new AppautoPackageController(client, guard);

    private AppautoAppView view() {
        LocalDateTime now = LocalDateTime.now();
        return new AppautoAppView(7L, 3L, "演示商城", "com.demo.shop", "1.2.3", "ANDROID",
                null, "project/3/app-package/shop.apk", 2048L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "MANUAL", now, now);
    }

    @Test
    void 列表_查看闸按项目成员_pkgview消费位() {
        when(client.listAppPackages(3L)).thenReturn(List.of(view()));
        List<AppautoAppView> rows = controller.list(3L).getData();
        assertEquals(1, rows.size());
        assertEquals("com.demo.shop", rows.get(0).packageName(), "字段镜像直传禁加工");
        verify(guard).assertCanView(UserContext.current(), 3L);
        verify(client).listAppPackages(3L);
    }

    @Test
    void 建档_管理闸_请求体照样转发回档案id() {
        AppPackageSaveRequest request = new AppPackageSaveRequest(3L, "演示商城",
                "com.demo.shop", "1.2.3", "android", null);
        when(client.createAppPackage(request)).thenReturn(7L);
        assertEquals(7L, controller.create(request).getData());
        verify(guard).assertCanManage(UserContext.current(), 3L);
        verify(client).createAppPackage(request);
    }

    @Test
    void 详情_归属取core回显_禁信前端传归属() {
        when(client.findAppPackage(7L)).thenReturn(view());
        AppautoAppView got = controller.get(7L).getData();
        assertEquals(2048L, got.artifactSizeBytes());
        verify(guard).assertCanView(UserContext.current(), 3L);
    }

    @Test
    void 上传_multipart字节零丢失中转_按回显归属管理闸() throws Exception {
        byte[] apk = "APK-BYTES-demo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shop-1.2.3.apk", "application/vnd.android.package-archive", apk);
        when(client.findAppPackage(7L)).thenReturn(view());
        when(client.uploadAppPackage(eq(7L), eq("shop-1.2.3.apk"),
                eq("application/vnd.android.package-archive"), any(byte[].class)))
                .thenReturn("project/3/app-package/shop.apk");
        String key = controller.upload(7L, file).getData();
        assertEquals("project/3/app-package/shop.apk", key, "objectKey 直传");
        verify(guard).assertCanManage(UserContext.current(), 3L);
        verify(client).uploadAppPackage(7L, "shop-1.2.3.apk",
                "application/vnd.android.package-archive", apk);
    }

    @Test
    void 删档_按回显归属管理闸_转发即毕() {
        when(client.findAppPackage(7L)).thenReturn(view());
        controller.delete(7L);
        verify(guard).assertCanManage(UserContext.current(), 3L);
        verify(client).deleteAppPackage(7L);
    }

    @Test
    void core人话错误_原样上抛不吞不改写() {
        when(client.findAppPackage(99L))
                .thenThrow(new ServiceException("应用档案不存在（id=99）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.get(99L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        assertEquals("应用档案不存在（id=99）", ex.getMessage());
    }
}
