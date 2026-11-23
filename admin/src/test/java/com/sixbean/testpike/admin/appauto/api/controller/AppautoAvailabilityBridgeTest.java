package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineAvailabilityView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.executor.api.dto.ExecutorView;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoAvailabilityBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard guard =
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class);
    private final AppautoCatalogController controller = new AppautoCatalogController(client, guard);

    @Test
    void 可用性对账_includeDisabled透传_三字段完整投影() {
        when(client.listEngineAvailability(false)).thenReturn(List.of(
                new AppautoEngineAvailabilityView("MOBILEWRIGHT", true, 2, List.of()),
                new AppautoEngineAvailabilityView("APPIUM", false, 0,
                        List.of("当前在线执行器 2 台均未上报该引擎能力——请在执行器机安装 Appium 运行时并重新注册"))));
        List<AppautoEngineAvailabilityView> got = controller.listEngineAvailability(false).getData();
        assertEquals(2, got.size());
        assertTrue(got.get(0).available());
        assertEquals(2, got.get(0).onlineCount());
        verify(client).listEngineAvailability(eq(false));
    }

    @Test
    void 可用性对账_含停用管理面透传() {
        when(client.listEngineAvailability(true)).thenReturn(List.of(
                new AppautoEngineAvailabilityView("HYPIUM", false, 0, List.of("引擎已停用/下架"))));
        assertEquals(1, controller.listEngineAvailability(true).getData().size());
        verify(client).listEngineAvailability(eq(true));
    }

    @Test
    void 可用性对账_core人话异常原样上抛不吞() {
        when(client.listEngineAvailability(false)).thenThrow(new ServiceException(
                "引擎目录不可用（core 未就绪）", BizErrorCode.SERVICE_UNAVAILABLE));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listEngineAvailability(false));
        assertTrue(ex.getMessage().contains("core"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 执行器视图_尾追加capabilities组件_整record反序列化带能力面() {
        RecordComponent[] components = ExecutorView.class.getRecordComponents();
        List<String> names = java.util.Arrays.stream(components)
                .map(RecordComponent::getName).toList();
        assertTrue(names.contains("capabilities"),
                "ExecutorView 必须含 capabilities 组件（core 能力面投影，前端引擎卡片灯源）: " + names);
        assertEquals(components.length, names.size());
        assertEquals("capabilities", names.get(names.size() - 1), "capabilities 必须尾追加（不破坏既有字段序）");
        ExecutorView view = new ExecutorView(1L, "ex-1", null, null, "web,app", "chromium",
                2, 0, 1, "1.0.0", "2026-10-02 12:00:00",
                Map.of("engines", List.of("MOBILEWRIGHT")));
        assertEquals(List.of("MOBILEWRIGHT"), view.capabilities().get("engines"));
    }
}
