package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterComponentView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreClusterComponentClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ClusterComponentBridgeTest {

    private final CoreClusterComponentClient client = mock(CoreClusterComponentClient.class);
    private final ClusterComponentController controller = new ClusterComponentController(client);

    @Test
    void 台账桥_十字段完整投影_灰态null原样透出() {
        LocalDateTime now = LocalDateTime.now();
        when(client.listComponents()).thenReturn(List.of(
                new ClusterComponentView(1L, "node-a", "engine-runtime", "1.2.3", "1.2.3",
                        ">=1.0", "GREEN", "已追平基准", "heartbeat", now, "https://appium.io"),
                new ClusterComponentView(2L, "node-b", "executor", null, "0.9.0",
                        ">=0.8", "UNKNOWN", "节点未上报现值（灰=缺证据禁猜绿）", "scan", now, null)));
        List<ClusterComponentView> got = controller.list().getData();
        assertEquals(2, got.size());
        ClusterComponentView green = got.get(0);
        assertEquals("node-a", green.nodeId());
        assertEquals("engine-runtime", green.componentFamily());
        assertEquals("1.2.3", green.currentVersion());
        assertEquals(">=1.0", green.requirement());
        assertEquals("GREEN", green.supportState());
        assertEquals("已追平基准", green.stateReason());
        assertEquals("heartbeat", green.source());
        assertEquals(now, green.reportedAt());
        assertNull(got.get(1).currentVersion(), "灰态现值=null 桥面禁填占位谎报");
        assertEquals("节点未上报现值（灰=缺证据禁猜绿）", got.get(1).stateReason());
        verify(client).listComponents();
    }

    @Test
    void 台账桥_core人话异常原样上抛() {
        when(client.listComponents()).thenThrow(new ServiceException("台账读取失败：库连不上"));
        assertThrows(ServiceException.class, () -> controller.list());
    }
}
