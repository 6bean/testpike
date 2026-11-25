package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;
import com.sixbean.testpike.core.cluster.domain.service.ClusterCommandService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterEnrollTokenService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ClusterCommandInternalControllerTest {

    private final ClusterCommandService commandService = mock(ClusterCommandService.class);
    private final ClusterEnrollTokenService tokenService = mock(ClusterEnrollTokenService.class);
    private final ClusterCommandInternalController controller =
            new ClusterCommandInternalController(commandService, tokenService);

    private static ClusterCommandView sample(Long id) {
        return new ClusterCommandView(id, "engine-install", "executor", 7L,
                Map.of("engines", List.of("MOBILEWRIGHT")), 1, 7L, null, null,
                LocalDateTime.now(), LocalDateTime.now());
    }

    @Test
    void 入队_原样构造输入转服务_视图回显() {
        when(commandService.enqueue(any())).thenReturn(sample(101L));
        Map<String, Object> body = Map.of(
                "commandType", "engine-install", "targetType", "executor", "targetId", 7L,
                "payload", Map.of("engines", List.of("MOBILEWRIGHT")), "operator", "bowen");
        ClusterCommandView got = controller.enqueue(body).getData();
        assertEquals(101L, got.id());
        ArgumentCaptor<ClusterCommandInput> cap = ArgumentCaptor.forClass(ClusterCommandInput.class);
        verify(commandService).enqueue(cap.capture());
        assertEquals("engine-install", cap.getValue().commandType());
        assertEquals(7L, cap.getValue().targetId());
        assertEquals("bowen", cap.getValue().operator());
    }

    @Test
    void 列表_带与不带目标过滤都透传() {
        when(commandService.list(7L)).thenReturn(List.of(sample(1L)));
        when(commandService.list(null)).thenReturn(List.of(sample(1L), sample(2L)));
        assertEquals(1, controller.list(7L).getData().size());
        assertEquals(2, controller.list(null).getData().size());
    }

    @Test
    void 领取_executor形态_按执行器id直领() {
        when(commandService.pollClaimed(7L, 5)).thenReturn(List.of(sample(1L)));
        List<ClusterCommandView> got = controller.poll(Map.of("executorId", 7, "limit", 5))
                .getData();
        assertEquals(1, got.size());
        verify(tokenService, never()).resolveTokenIdByDigest(any());
    }

    @Test
    void 领取_token形态_按摘要解析令牌行再按指纹领() {
        when(tokenService.resolveTokenIdByDigest("d".repeat(64))).thenReturn(88L);
        when(commandService.pollClaimedForEnroll(88L, "fp-1", 3)).thenReturn(List.of(sample(2L)));
        List<ClusterCommandView> got = controller.poll(
                Map.of("tokenDigest", "d".repeat(64), "fingerprint", "fp-1", "limit", 3)).getData();
        assertEquals(1, got.size());
        verify(commandService, never()).pollClaimed(anyLong(), anyInt());
    }

    @Test
    void 领取_token摘要解析不到_空集不炸() {
        when(tokenService.resolveTokenIdByDigest("unknown")).thenReturn(null);
        List<ClusterCommandView> got = controller.poll(
                Map.of("tokenDigest", "unknown", "fingerprint", "fp-1")).getData();
        assertTrue(got.isEmpty());
        verify(commandService, never()).pollClaimedForEnroll(anyLong(), any(), anyInt());
    }

    @Test
    void 进度_四件原样透传() {
        controller.progress(Map.of("commandId", 9, "claimerId", 7,
                "step", "deps", "message", "装依赖中"));
        verify(commandService).progress(9L, 7L, "deps", "装依赖中", null, null);
    }

    @Test
    void 回执_成败与人话详情透传() {
        controller.result(Map.of("commandId", 9, "claimerId", 7,
                "success", true, "resultDetail", "引擎已就位"));
        verify(commandService).complete(9L, 7L, true, "引擎已就位", null, null);
    }

    @Test
    void 回执缺成败字段_分型人话拒() {
        Map<String, Object> body = new HashMap<>();
        body.put("commandId", 9);
        body.put("claimerId", 7);
        assertThrows(ServiceException.class, () -> controller.result(body));
    }

    @Test
    void 领取双缺身份_分型人话拒() {
        assertThrows(ServiceException.class, () -> controller.poll(Map.of("limit", 5)));
    }
}
