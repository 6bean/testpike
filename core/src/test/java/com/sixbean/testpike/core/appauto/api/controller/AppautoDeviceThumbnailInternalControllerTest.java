package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceThumbnailService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoDeviceThumbnailInternalControllerTest {

    private final AppautoDeviceThumbnailService thumbnailService =
            mock(AppautoDeviceThumbnailService.class);
    private final AppautoDeviceThumbnailInternalController controller =
            new AppautoDeviceThumbnailInternalController(thumbnailService);

    @Test
    void 实况格_设备id原样透传服务_视图字段原样回显() {
        AppautoDeviceThumbnailView live = new AppautoDeviceThumbnailView(
                9L, 42L, AppautoDeviceThumbnailView.STATE_LIVE, null,
                "aGVsbG8=", 360, 800, 1080, 2400);
        when(thumbnailService.thumbnail(9L)).thenReturn(live);
        AppautoDeviceThumbnailView got = controller.thumbnail(9L).getData();
        verify(thumbnailService).thumbnail(9L);
        assertSame(live, got);
        assertEquals(42L, got.taskId());
        assertEquals("LIVE", got.state());
        assertEquals("aGVsbG8=", got.imageBase64());
        assertEquals(1080, got.sourceWidth());
    }

    @Test
    void 诚实非实况格_无图带reason照常透出_非错误() {
        AppautoDeviceThumbnailView idle = AppautoDeviceThumbnailView.withoutFrame(
                9L, null, AppautoDeviceThumbnailView.STATE_IDLE, "该设备当前没有运行中的任务");
        when(thumbnailService.thumbnail(anyLong())).thenReturn(idle);
        AppautoDeviceThumbnailView got = controller.thumbnail(9L).getData();
        assertEquals("IDLE", got.state());
        assertNull(got.imageBase64());
        assertEquals("该设备当前没有运行中的任务", got.reason());
    }

    @Test
    void 未知设备_服务人话404原样上抛_本口禁捕获加工() {
        ServiceException boom = new ServiceException("设备不存在", BizErrorCode.NOT_FOUND);
        when(thumbnailService.thumbnail(anyLong())).thenThrow(boom);
        ServiceException thrown = assertThrows(ServiceException.class,
                () -> controller.thumbnail(999L));
        assertSame(boom, thrown);
    }
}
