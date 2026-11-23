package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineView;
import com.sixbean.testpike.admin.appauto.api.dto.AppautoPlatformView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoCatalogBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard guard =
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class);
    private final AppautoCatalogController controller = new AppautoCatalogController(client, guard);

    private AppautoEngineView engine(String code) {
        return new AppautoEngineView(1L, code, "Mobilewright", List.of("ANDROID", "IOS"),
                List.of("PHONE"), 10, "PY_INPROC", Map.of("python", ">=3.11"),
                Map.of("tap", true, "input-text", true),
                List.of("pip install mobilewright（禁开遥测上报）"),
                List.of(Map.of("cmd", "python -c 'import mobilewright'")),
                Map.of("version_pin", "0.0.63"),
                1, "https://example.org/docs", "Apache-2.0", null, null);
    }

    private AppautoPlatformView platform(String code) {
        return new AppautoPlatformView(2L, code, "Android", List.of("PHONE"),
                List.of("apk"), "AOSP 兼容鸿蒙可选安卓档案并填设备类型防撞", 1, null, null);
    }

    @Test
    void 引擎列表_启用面参数透传_字段完整不截断() {
        when(client.listEngines(false)).thenReturn(List.of(engine("MOBILEWRIGHT")));
        AppautoEngineView got = controller.listEngines(false).getData().get(0);
        assertEquals("Mobilewright", got.name());
        assertEquals(Boolean.TRUE, got.capabilities().get("tap"), "动词位图必须完整反序列化透传");
        assertTrue(got.driverRequirements().get(0).contains("遥测"), "人话清单不得截断");
        assertEquals("0.0.63", got.installRecipe().get("version_pin"),
                "安装配方对象结构必须原样透传（installkit 消费面禁二套解析）");
        verify(client).listEngines(false);
    }

    @Test
    void 引擎列表_含停用候补位透传_core单点过滤() {
        when(client.listEngines(true)).thenReturn(List.of(engine("MOBILEWRIGHT"), engine("HYPIUM")));
        assertEquals(2, controller.listEngines(true).getData().size());
        verify(client).listEngines(eq(true));
    }

    @Test
    void 引擎详情_按code透传_core人话404原样上抛() {
        when(client.findEngine("AIRTEST")).thenReturn(engine("AIRTEST"));
        assertEquals("AIRTEST", controller.getEngine("AIRTEST").getData().code());
        when(client.findEngine("NOPE")).thenThrow(new ServiceException(
                "引擎不存在：NOPE（引擎编码请从目录选择）", BizErrorCode.NOT_FOUND));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.getEngine("NOPE"));
        assertTrue(ex.getMessage().contains("引擎不存在"), "core 人话文案禁加工禁吞");
    }

    @Test
    void 平台列表与详情_透传() {
        when(client.listPlatforms(false)).thenReturn(List.of(platform("ANDROID")));
        when(client.findPlatform("ANDROID")).thenReturn(platform("ANDROID"));
        assertEquals(1, controller.listPlatforms(false).getData().size());
        assertEquals("Android", controller.getPlatform("ANDROID").getData().name());
        verify(client).listPlatforms(anyBoolean());
    }
}
