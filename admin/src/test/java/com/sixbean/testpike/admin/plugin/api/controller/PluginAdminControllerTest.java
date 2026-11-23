package com.sixbean.testpike.admin.plugin.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.plugin.api.dto.AppliedProjectView;
import com.sixbean.testpike.admin.plugin.api.dto.PluginEnableInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginUpdateInput;
import com.sixbean.testpike.admin.plugin.api.dto.PluginView;
import com.sixbean.testpike.admin.plugin.infrastructure.remote.client.CorePluginClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class PluginAdminControllerTest {

    private final CorePluginClient client = mock(CorePluginClient.class);
    private final PluginAdminController controller = new PluginAdminController(client);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static void login(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "管理员",
                List.of("ADMIN"), System.currentTimeMillis() + 3600_000, null));
    }

    private static PluginView viewFixture() {
        return new PluginView(9L, "pos-demo", "POS", "1.0.0", true, 2,
                Map.of("name", "pos-demo", "version", "1.0.0"), 3);
    }

    @Test
    void 清单筛选参数按位透传core() {
        login(7L);
        when(client.list("POS", true)).thenReturn(List.of(viewFixture()));
        Response<List<PluginView>> resp = controller.list("POS", true);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().size());
        verify(client).list("POS", true);
    }

    @Test
    void 上传文件_UTF8转文本零丢失_操作人透传() throws IOException {
        login(7L);
        String yaml = "name: pos-demo\nindustry: POS\ndescription: 中文描述——禁转码截断\n";
        MockMultipartFile file = new MockMultipartFile("file", "plugin.yaml",
                "application/x-yaml", yaml.getBytes(StandardCharsets.UTF_8));
        when(client.register(anyLong(), anyString())).thenReturn(viewFixture());
        Response<PluginView> resp = controller.register(file);
        assertEquals("0", resp.getCode());
        ArgumentCaptor<String> content = ArgumentCaptor.forClass(String.class);
        verify(client).register(eq(7L), content.capture());
        assertEquals(yaml, content.getValue(), "文件→文本必须逐字符一致（含中文与尾换行）");
    }

    @Test
    void 启停_id与开关透传_PUT幂等语义归core() {
        login(7L);
        when(client.setEnabled(7L, 9L, new PluginEnableInput(false))).thenReturn(viewFixture());
        Response<PluginView> resp = controller.setEnabled(9L, new PluginEnableInput(false));
        assertEquals("0", resp.getCode());
        verify(client).setEnabled(7L, 9L, new PluginEnableInput(false));
    }

    @Test
    void core视图JSON字段对齐_manifest嵌套零静默丢() throws Exception {
        String coreJson = """
                {"id":9,"name":"pos-demo","industry":"POS","version":"1.0.0",
                 "enabled":true,"source":2,"revision":3,
                 "manifest":{"name":"pos-demo","industry":"POS","version":"1.0.0",
                             "description":"d","skills":[],"params":{},"enabled":true}}""";
        PluginView view = mapper.readValue(coreJson, PluginView.class);
        assertEquals(9L, view.id());
        assertEquals("pos-demo", view.name());
        assertEquals(2, view.source(), "来源列静默丢=内置只读标识缺失");
        assertEquals(7, view.manifest().size(), "manifest 嵌套静默丢=前端插件详情空");
        assertEquals("d", view.manifest().get("description"));
        assertEquals(3, view.revision(), "revision 静默丢=前端编辑乐观锁必填位缺失（T242 V15）");
    }

    @Test
    void 详情_id透传core桥() {
        login(7L);
        when(client.detail(7L, 9L)).thenReturn(viewFixture());
        Response<PluginView> resp = controller.detail(9L);
        assertEquals("0", resp.getCode());
        assertEquals(3, resp.getData().revision());
        verify(client).detail(7L, 9L);
    }

    @Test
    void 编辑_id与revision与content按位透传_409语义归core() {
        login(7L);
        String yaml = "name: pos-demo\nindustry: POS\nversion: 1.0.1\n";
        when(client.update(eq(7L), eq(9L), any())).thenReturn(viewFixture());
        Response<PluginView> resp = controller.update(9L, new PluginUpdateInput(3, yaml));
        assertEquals("0", resp.getCode());
        ArgumentCaptor<PluginUpdateInput> cap = ArgumentCaptor.forClass(PluginUpdateInput.class);
        verify(client).update(eq(7L), eq(9L), cap.capture());
        assertEquals(3, cap.getValue().revision(), "revision 丢=core 乐观锁必填位缺失被 40001 误拒");
        assertEquals(yaml, cap.getValue().content(), "描述符必须逐字符到达 core 校验闸口");
    }

    @Test
    void 装配项目反查_id透传空集不异常() {
        login(7L);
        when(client.appliedProjects(7L, 9L)).thenReturn(List.of());
        Response<List<AppliedProjectView>> resp = controller.appliedProjects(9L);
        assertEquals("0", resp.getCode());
        assertTrue(resp.getData().isEmpty(), "无装配=空集透传（core 契约零异常，桥禁补 404）");
        verify(client).appliedProjects(7L, 9L);
    }
}
