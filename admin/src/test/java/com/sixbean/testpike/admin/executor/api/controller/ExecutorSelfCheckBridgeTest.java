package com.sixbean.testpike.admin.executor.api.controller;

import com.sixbean.testpike.admin.cluster.api.dto.ClusterComponentView;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorSelfCheckView;
import com.sixbean.testpike.admin.executor.infrastructure.remote.client.CoreExecClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


class ExecutorSelfCheckBridgeTest {

    private final CoreExecClient coreExecClient = mock(CoreExecClient.class);
    private final ExecutorController controller = new ExecutorController(coreExecClient,
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class));

    private static ExecutorSelfCheckView sample() {
        return new ExecutorSelfCheckView(7L, "web-node-01", "1.4.2", 1, "在线",
                "2026-10-11 20:01:33", true, "linux",
                Map.of("adb", true, "appium", false),
                List.of("appium"),
                new java.util.LinkedHashMap<>(Map.of("python", "3.11.9")),
                List.of("PLAYWRIGHT", "SELENIUM"),
                List.of("web", "android"),
                List.of("ANDROID_EMULATOR"),
                3L,
                List.of(new ClusterComponentView(11L, "node-7", "k6", "0.58.0", "0.58.0",
                        ">=0.55", "GREEN", "满足当前发布要求", "heartbeat",
                        LocalDateTime.of(2026, 10, 11, 20, 1, 33), null)));
    }

    @Test
    void 自检报告桥字段原样透传禁双头加工() {
        when(coreExecClient.selfCheck(7L)).thenReturn(sample());

        Response<ExecutorSelfCheckView> resp = controller.selfCheck(7L);

        assertEquals("0", resp.getCode());
        ExecutorSelfCheckView v = resp.getData();
        assertEquals(7L, v.executorId());
        assertEquals("web-node-01", v.name());
        assertEquals("1.4.2", v.version());
        assertEquals(1, v.status(), "在线态码原样透传（配色判定依据）");
        assertEquals("在线", v.statusText());
        assertEquals("2026-10-11 20:01:33", v.lastHeartbeat(), "心跳=core 格式化原样");
        assertEquals(true, v.capabilitiesReported());
        assertEquals("linux", v.hostOs());
        assertEquals(Boolean.FALSE, v.tools().get("appium"), "工具在否 map 原样");
        assertEquals(List.of("appium"), v.missingTools(), "缺件点名=core 单点推导，桥禁重算");
        assertEquals(List.of("PLAYWRIGHT", "SELENIUM"), v.engines(), "推荐引擎=core 判定原样");
        assertEquals(List.of("web", "android"), v.platforms());
        assertEquals(List.of("ANDROID_EMULATOR"), v.deviceKinds());
        assertEquals(3L, v.deviceCount(), "建档设备数=core 标签口径计数");
        ClusterComponentView comp = v.components().get(0);
        assertEquals("GREEN", comp.supportState(), "三色=core 判定谱投影，桥禁二次判定");
        assertEquals("满足当前发布要求", comp.stateReason(), "人话理由完整透出禁截断");
    }

    @Test
    void 能力面未上报执行器诚实空值桥不谎报() {
        ExecutorSelfCheckView legacy = new ExecutorSelfCheckView(9L, "old-node", "1.0.0", 3, "离线",
                null, false, null, Map.of(), List.of(), Map.of(), List.of(), List.of(),
                List.of(), null, List.of());
        when(coreExecClient.selfCheck(9L)).thenReturn(legacy);

        Response<ExecutorSelfCheckView> resp = controller.selfCheck(9L);

        ExecutorSelfCheckView v = resp.getData();
        assertEquals(false, v.capabilitiesReported());
        assertEquals(0, v.tools().size(), "未上报=诚实空 map 禁填充");
        assertEquals(0, v.missingTools().size(), "未上报禁谎报全缺");
        assertEquals(null, v.deviceCount(), "hostOs 未知=null，禁拿全表计数冒充");
    }

    @Test
    void 自检报告端点挂H4语义键防双闸() {
        Method m;
        try {
            m = ExecutorController.class.getMethod("selfCheck", long.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("自检报告桥端点缺失（签名先于实现核对）：" + e.getMessage(), e);
        }
        RequirePermission ann = m.getAnnotation(RequirePermission.class);
        assertNotNull(ann, "自检报告端点必须挂权限键（无裸端点）");
        assertEquals("perm:project:executor-pool", ann.value(),
                "执行器池详情数据面与执行器列表同功能点同键，禁两套闸");
    }
}
