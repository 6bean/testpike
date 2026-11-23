package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.core.cluster.api.dto.ClusterComponentInfo;
import com.sixbean.testpike.core.cluster.domain.service.ClusterComponentService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ClusterComponentInternalControllerTest {

    private final ClusterComponentService service = mock(ClusterComponentService.class);
    private final ClusterComponentInternalController controller =
            new ClusterComponentInternalController(service);

    @Test
    void 台账全量_十字段原样透出_三色与理由不加工() {
        LocalDateTime now = LocalDateTime.now();
        when(service.listAll()).thenReturn(List.of(
                new ClusterComponentInfo(1L, "node-a", "engine-runtime", "1.2.3", "1.2.3",
                        ">=1.0", "GREEN", "已追平基准", "heartbeat", now, "https://appium.io"),
                new ClusterComponentInfo(2L, "node-b", "executor", null, "0.9.0",
                        ">=0.8", "UNKNOWN", "节点未上报现值（灰=缺证据禁猜绿）", "scan", now, null)));
        List<ClusterComponentInfo> got = controller.list().getData();
        assertEquals(2, got.size());
        ClusterComponentInfo green = got.get(0);
        assertEquals("node-a", green.nodeId());
        assertEquals("GREEN", green.supportState());
        assertEquals("已追平基准", green.stateReason());
        assertEquals("heartbeat", green.source());
        assertEquals(now, green.reportedAt());
        ClusterComponentInfo grey = got.get(1);
        assertEquals("UNKNOWN", grey.supportState());
        assertEquals("节点未上报现值（灰=缺证据禁猜绿）", grey.stateReason());
        verify(service).listAll();
    }

    @Test
    void 台账空_空集合非null() {
        when(service.listAll()).thenReturn(List.of());
        List<ClusterComponentInfo> got = controller.list().getData();
        assertEquals(0, got.size());
    }

    @Test
    void 服务异常_原样上抛不吞() {
        when(service.listAll()).thenThrow(new IllegalStateException("库连不上"));
        assertThrows(IllegalStateException.class, () -> controller.list());
    }
}
