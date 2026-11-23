package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoEngineHealthView;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AppautoEngineHealthBridgeTest {

    private final CoreAppautoClient client = mock(CoreAppautoClient.class);
    private final com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard guard =
            mock(com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard.class);
    private final AppautoCatalogController controller = new AppautoCatalogController(client, guard);

    @Test
    void 体检差集_includeDisabled透传_七字段完整投影() {
        when(client.listEngineHealth(false)).thenReturn(List.of(
                new AppautoEngineHealthView("MOBILEWRIGHT", "Mobilewright", true, 2, 2,
                        List.of(), false),
                new AppautoEngineHealthView("APPIUM", "Appium", true, 2, 0,
                        List.of("运行时版本不满足：描述符要求 >=2.0，执行器 ex-1 实测 1.23.0——请升级 appium-python-client",
                                "探针面覆盖缺口：wx_devtools 端口探测本面对账不到——请在执行器机自装微信开发者工具后重试体检"),
                        false)));
        List<AppautoEngineHealthView> got = controller.listEngineHealth(false).getData();
        assertEquals(2, got.size());
        AppautoEngineHealthView ok = got.get(0);
        assertTrue(ok.enabled());
        assertEquals(2, ok.onlineExecutors());
        assertEquals(2, ok.readyExecutors());
        assertTrue(ok.blockingItems().isEmpty(), "全绿引擎差集必为空");
        assertFalse(ok.aiDoctorEnabled(), "体检键默认关，桥面禁伪造开");
        AppautoEngineHealthView bad = got.get(1);
        assertEquals(0, bad.readyExecutors());
        assertEquals(2, bad.blockingItems().size(), "人话差集清单禁截断");
        assertTrue(bad.blockingItems().get(0).contains("appium-python-client"));
        verify(client).listEngineHealth(eq(false));
    }

    @Test
    void 体检差集_含停用管理面透传() {
        when(client.listEngineHealth(true)).thenReturn(List.of(
                new AppautoEngineHealthView("HYPIUM", "hypium", false, 0, 0,
                        List.of("引擎已停用/下架（上下架原因见描述符）"), false)));
        List<AppautoEngineHealthView> got = controller.listEngineHealth(true).getData();
        assertEquals(1, got.size());
        assertFalse(got.get(0).enabled(), "停用行 includeDisabled=true 才出现且如实带停用旗标");
        verify(client).listEngineHealth(eq(true));
    }

    @Test
    void 体检差集_core人话异常原样上抛不吞() {
        when(client.listEngineHealth(false)).thenThrow(new ServiceException(
                "引擎目录不可用（core 未就绪）", BizErrorCode.SERVICE_UNAVAILABLE));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.listEngineHealth(false));
        assertTrue(ex.getMessage().contains("core"), "core 人话文案禁加工禁吞");
    }
}
