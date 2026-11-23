package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInfo;
import com.sixbean.testpike.core.appauto.api.dto.AppautoAppInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoAppService;
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


class AppautoAppInternalControllerTest {

    private final AppautoAppService appService = mock(AppautoAppService.class);
    private final AppautoAppInternalController controller = new AppautoAppInternalController(appService);

    private AppautoAppInfo info() {
        LocalDateTime now = LocalDateTime.now();
        return new AppautoAppInfo(7L, 3L, "演示商城", "com.demo.shop", "1.2.3", "ANDROID",
                null, "project/3/app-package/shop.apk", 2048L,
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "MANUAL", now, now);
    }

    @Test
    void 建档_项目与入参照样转发_回档案id() {
        AppautoAppInput input = new AppautoAppInput("演示商城", "com.demo.shop", "1.2.3", "android", null);
        when(appService.create(3L, input)).thenReturn(7L);
        assertEquals(7L, controller.create(3L, input).getData(), "建档返回 id 直传，core 单点语义");
        verify(appService).create(3L, input);
    }

    @Test
    void 详情_档案字段原样透出_体积摘要来源零加工() {
        when(appService.get(7L)).thenReturn(info());
        AppautoAppInfo got = controller.get(7L).getData();
        assertEquals(2048L, got.artifactSizeBytes(), "实测体积=core 复位值原样");
        assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                got.artifactSha256(), "SHA-256 摘要直传禁改写");
        assertEquals("MANUAL", got.source(), "来源位原样（CI=预留值本波不产）");
    }

    @Test
    void 列表_按项目转发_H1隔离判在service单点() {
        when(appService.list(3L)).thenReturn(List.of(info()));
        List<AppautoAppInfo> rows = controller.list(3L).getData();
        assertEquals(1, rows.size());
        assertEquals("com.demo.shop", rows.get(0).packageName());
        verify(appService).list(3L);
    }

    @Test
    void 上传_multipart字节零丢失中转_文件名随行_objectKey直传() throws Exception {
        byte[] apk = "APK-BYTES-demo".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "shop-1.2.3.apk", "application/octet-stream", apk);
        when(appService.uploadPackage(eq(7L), any(byte[].class), eq("shop-1.2.3.apk")))
                .thenReturn("project/3/app-package/shop.apk");
        String key = controller.uploadPackage(7L, file).getData();
        assertEquals("project/3/app-package/shop.apk", key, "归档 objectKey 直传");
        verify(appService).uploadPackage(7L, apk, "shop-1.2.3.apk");
    }

    @Test
    void 上传_空内容core人话拒_原样上抛不吞() throws Exception {
        MockMultipartFile empty = new MockMultipartFile("file", "x.apk",
                "application/octet-stream", new byte[0]);
        when(appService.uploadPackage(any(Long.class), any(byte[].class), any()))
                .thenThrow(new ServiceException("包文件内容为空，拒收", BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.uploadPackage(7L, empty));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "40001 码原样（尺寸闸在 service 单点）");
        assertEquals("包文件内容为空，拒收", ex.getMessage(), "人话文案禁改写");
    }

    @Test
    void 删档_转发即毕_core不存在人话404原样() {
        org.mockito.Mockito.doThrow(new ServiceException("应用档案不存在（id=99）", BizErrorCode.NOT_FOUND))
                .when(appService).delete(99L);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.delete(99L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
        verify(appService).delete(99L);
    }
}
