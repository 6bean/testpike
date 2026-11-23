package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.core.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoTakeoverService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoTakeoverInternalControllerTest {

    private final AppautoTakeoverService takeoverService = mock(AppautoTakeoverService.class);
    private final AppautoTakeoverInternalController controller =
            new AppautoTakeoverInternalController(takeoverService);

    private static AppautoTakeoverView view(long id, String state) {
        return new AppautoTakeoverView(id, 5L, 9L, 11L, 100L, 200L, state, "联调需要",
                null, null, LocalDateTime.now().plusMinutes(5), null, LocalDateTime.now());
    }

    @Test
    void 发起申请_入参原样转服务_视图回显() {
        TakeoverCreateInput input = new TakeoverCreateInput(5L, 11L, 100L, "联调需要");
        when(takeoverService.request(any())).thenReturn(view(1L, "pending"));
        AppautoTakeoverView got = controller.request(input).getData();
        assertEquals(1L, got.id());
        assertEquals("pending", got.state());
        ArgumentCaptor<TakeoverCreateInput> cap = ArgumentCaptor.forClass(TakeoverCreateInput.class);
        verify(takeoverService).request(cap.capture());
        assertEquals(5L, cap.getValue().deviceId());
        assertEquals(11L, cap.getValue().taskId());
        assertEquals(100L, cap.getValue().requesterId());
    }

    @Test
    void 同意_申请id与判定人原样透传() {
        when(takeoverService.agree(7L, 3L)).thenReturn(view(7L, "active"));
        AppautoTakeoverView got = controller.agree(7L, 3L).getData();
        assertEquals("active", got.state());
        verify(takeoverService).agree(7L, 3L);
    }

    @Test
    void 驳回_申请id与判定人原样透传() {
        when(takeoverService.reject(8L, 3L)).thenReturn(view(8L, "rejected"));
        assertEquals("rejected", controller.reject(8L, 3L).getData().state());
        verify(takeoverService).reject(8L, 3L);
    }

    @Test
    void 强接_申请id与管理操作人原样透传() {
        when(takeoverService.force(9L, 1L)).thenReturn(view(9L, "forced"));
        assertEquals("forced", controller.force(9L, 1L).getData().state());
        verify(takeoverService).force(9L, 1L);
    }

    @Test
    void 结束_申请id与操作人原样透传() {
        when(takeoverService.end(10L, 100L)).thenReturn(view(10L, "ended"));
        assertEquals("ended", controller.end(10L, 100L).getData().state());
        verify(takeoverService).end(10L, 100L);
    }

    @Test
    void 读取_仅按id转发_判定链零加戏() {
        AppautoTakeoverView sample = view(11L, "pending");
        when(takeoverService.get(11L)).thenReturn(sample);
        assertSame(sample, controller.get(11L).getData());
        verify(takeoverService).get(11L);
        verify(takeoverService, org.mockito.Mockito.never()).agree(anyLong(), anyLong());
    }
}
