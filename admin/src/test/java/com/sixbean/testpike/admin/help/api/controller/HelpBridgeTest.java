package com.sixbean.testpike.admin.help.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.help.api.dto.HelpDocView;
import com.sixbean.testpike.admin.help.infrastructure.remote.client.CoreHelpClient;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class HelpBridgeTest {

    private final CoreHelpClient client = mock(CoreHelpClient.class);
    private final HelpController controller = new HelpController(client);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 帮助视图字段与core契约对齐不静默丢() throws Exception {
        String coreJson = """
                {"id":1,"domainCode":"V","title":"执行与证据","bodyMd":"## 正文",\
                "sortNo":1,"version":"v2.40","updatedAt":"2026-09-21 10:00:00"}""";
        HelpDocView view = mapper.readValue(coreJson, HelpDocView.class);
        assertEquals(1L, view.id());
        assertEquals("V", view.domainCode());
        assertEquals("执行与证据", view.title());
        assertEquals("## 正文", view.bodyMd());
        assertEquals(1, view.sortNo());
        assertEquals("v2.40", view.version());
        assertEquals("2026-09-21 10:00:00", view.updatedAt());
    }

    @Test
    void 列表按位透传且列表态无正文() {
        HelpDocView index = new HelpDocView(1L, "A", "用例管理", null, 1, "v2.40",
                "2026-09-21 10:00:00");
        when(client.list()).thenReturn(List.of(index));
        Response<List<HelpDocView>> resp = controller.list();
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        assertNull(resp.getData().get(0).bodyMd(), "列表态正文必须为 null（core 不传，桥层禁补）");
        verify(client).list();
    }

    @Test
    void 详情域码透传且正文携带() {
        HelpDocView doc = new HelpDocView(2L, "V", "执行与证据", "正文全文", 1, "v2.40",
                "2026-09-21 10:00:00");
        when(client.detail(eq("V"))).thenReturn(List.of(doc));
        Response<List<HelpDocView>> resp = controller.detail("V");
        assertEquals("正文全文", resp.getData().get(0).bodyMd());
        verify(client).detail("V");
    }

    @Test
    void 不存在域返回空列表透传非异常() {
        when(client.detail(eq("Z9"))).thenReturn(List.of());
        Response<List<HelpDocView>> resp = controller.detail("Z9");
        assertEquals("0", resp.getCode());
        assertEquals(List.of(), resp.getData());
    }

    @Test
    void 两端点零权限标注登录即可读() throws Exception {
        Method[] endpoints = {
                HelpController.class.getDeclaredMethod("list"),
                HelpController.class.getDeclaredMethod("detail", String.class)};
        for (Method m : endpoints) {
            assertNull(m.getAnnotation(RequirePermission.class),
                    m.getName() + " 不得标注 @RequirePermission（V12 读侧登录即可，零新权限键）");
        }
    }
}
