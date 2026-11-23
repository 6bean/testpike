package com.sixbean.testpike.admin.tool.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.admin.tool.api.dto.ToolMarketItemView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ToolMarketBridgeTest {

    private final ToolClient client = mock(ToolClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final ToolAccessGuard guard = new ToolAccessGuard(projectGuard);
    private final ToolMarketController controller = new ToolMarketController(client, guard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(String... roles) {
        CurrentUser user = new CurrentUser(42L, "u42", "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }


    @Test
    void 市场列表参数按位透传core含category() {
        login("TESTER");
        when(client.marketList(2, 10, "取数", 2, "data", "downloadCount", "desc"))
                .thenReturn(new PageView<>(List.of(), 0L));
        controller.list(2, 10, "取数", 2, "data", "downloadCount", "desc");
        verify(client).marketList(2, 10, "取数", 2, "data", "downloadCount", "desc");
    }

    @Test
    void 市场行视图接收core十五字段含分类与用量三列() throws Exception {
        String coreJson = """
                {"id":9,"projectId":null,"name":"取数助手","type":1,"typeLabel":"文档类",
                 "category":"data","statusLabel":"已发布","currentVersion":2,"authorName":"张三",
                 "downloadCount":7,"refProjectCount":3,"call30dCount":128,
                 "tags":["回归","冒烟"],"bodyPreview":"## 用法","createdAt":"2026-09-10 01:00"}""";
        ToolMarketItemView view = mapper.readValue(coreJson, ToolMarketItemView.class);
        assertEquals(1, view.type());
        assertEquals("文档类", view.typeLabel());
        assertEquals("data", view.category());
        assertEquals(7, view.downloadCount());
        assertEquals(3, view.refProjectCount());
        assertEquals(128, view.call30dCount());
        assertEquals("张三", view.authorName());
        assertEquals(List.of("回归", "冒烟"), view.tags());
    }


    @Test
    void 下载透传返回带签URL() {
        login("TESTER");
        ToolMarketDownloadView downloaded = new ToolMarketDownloadView(9L, "zip",
                "取数助手.zip", "tp/0/tool-pack/202609/ab12.zip",
                "http://localhost:9000/testpike/tp/0/tool-pack/202609/ab12.zip?X-Amz-Signature=x");
        when(client.marketDownload(9L, "zip")).thenReturn(downloaded);
        Response<ToolMarketDownloadView> resp = controller.download(9L, "zip");
        assertEquals(downloaded.url(), resp.getData().url());
        assertEquals("取数助手.zip", resp.getData().fileName());
        verify(client).marketDownload(9L, "zip");
    }

    @Test
    void 市场视图JSON经Response包装字段完整() throws Exception {
        String wrapped = """
                {"code":"0","msg":"ok","data":{"list":[{"id":9,"projectId":7,"name":"n",
                 "type":1,"typeLabel":"文档类","category":"api","statusLabel":"已发布",
                 "currentVersion":1,"authorName":"李四","downloadCount":0,"refProjectCount":1,
                 "call30dCount":0,"tags":[],"bodyPreview":"p","createdAt":"2026-09-10"}],
                 "total":1}}""";
        Response<PageView<ToolMarketItemView>> resp = mapper.readValue(wrapped,
                mapper.getTypeFactory().constructParametricType(Response.class,
                        mapper.getTypeFactory().constructParametricType(PageView.class,
                                ToolMarketItemView.class)));
        assertEquals(1L, resp.getData().total());
        ToolMarketItemView row = resp.getData().list().get(0);
        assertEquals(7L, row.projectId());
        assertEquals("api", row.category());
        assertEquals(0, row.downloadCount());
    }


    @Test
    void 导入字节与文件名零丢失透传() throws Exception {
        login("ADMIN");
        byte[] zip = new byte[]{0x50, 0x4b, 0x03, 0x04, 0x00, (byte) 0xFF};
        MockMultipartFile file = new MockMultipartFile("file", "tool-pack.zip",
                "application/zip", zip);
        when(client.marketImport(isNull(), eq("tool-pack.zip"), any())).thenReturn(null);
        controller.importTool(file, null);
        ArgumentCaptor<byte[]> bytesCap = ArgumentCaptor.forClass(byte[].class);
        verify(client).marketImport(isNull(), eq("tool-pack.zip"), bytesCap.capture());
        assertArrayEquals(zip, bytesCap.getValue(), "导入字节必须原样透传（禁转码/截断）");
    }

    @Test
    void 导入项目级_透传projectId() throws Exception {
        login("TESTER");
        projectClient.memberRoleResult(2);
        MockMultipartFile json = new MockMultipartFile("file", "tool.json",
                "application/json", "{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        controller.importTool(json, 7L);
        verify(client).marketImport(eq(7L), eq("tool.json"), any());
    }

    @Test
    void 导入系统级_非ADMIN_403且不打core() {
        login("TESTER");
        MockMultipartFile json = new MockMultipartFile("file", "tool.json",
                "application/json", "x".getBytes(StandardCharsets.UTF_8));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importTool(json, null));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话: " + ex.getMessage());
        verifyNoInteractions(client);
    }

    @Test
    void 导入项目级_非成员_403() {
        login("TESTER");
        projectClient.memberRoleResult(0);
        MockMultipartFile json = new MockMultipartFile("file", "tool.json",
                "application/json", "x".getBytes(StandardCharsets.UTF_8));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.importTool(json, 7L));
        assertEquals(40301, ex.getCode());
        verifyNoInteractions(client);
    }
}
