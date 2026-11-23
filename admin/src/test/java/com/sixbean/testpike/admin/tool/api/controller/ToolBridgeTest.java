package com.sixbean.testpike.admin.tool.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.tool.api.dto.ToolConfirmTicketView;
import com.sixbean.testpike.admin.tool.api.dto.ToolCreateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvokeRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRejectRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolRollbackRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunInput;
import com.sixbean.testpike.admin.tool.api.dto.ToolTestRunResult;
import com.sixbean.testpike.admin.tool.api.dto.ToolUpdateRequest;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionDetailView;
import com.sixbean.testpike.admin.tool.api.dto.ToolVersionInfoView;
import com.sixbean.testpike.admin.tool.api.dto.ToolInvocationView;
import com.sixbean.testpike.admin.tool.api.dto.ToolView;
import com.sixbean.testpike.admin.tool.app.component.ToolAccessGuard;
import com.sixbean.testpike.admin.tool.infrastructure.remote.client.ToolClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ToolBridgeTest {

    private final ToolClient client = mock(ToolClient.class);
    private final StubCoreProjectClient projectClient = new StubCoreProjectClient();
    private final ProjectAccessGuard projectGuard = new ProjectAccessGuard(projectClient);
    private final ToolAccessGuard guard = new ToolAccessGuard(projectGuard);
    private final ToolController controller = new ToolController(client, guard, projectGuard);
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId, String... roles) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "测试人", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    private static ToolView tool(Long projectId, Long createdBy) {
        return new ToolView(9L, projectId, "取数助手", 2, "可执行脚本", "data", "描述", "正文预览",
                "[\"回归\"]", "{\"type\":\"object\"}", null, 1, "HTTP回调",
                "{\"url\":\"http://x\"}", 30000, 1, "草稿", 1, 3, createdBy, null,
                null, null, "2026-09-10 01:00", "2026-09-10 01:00");
    }


    @Test
    void 列表筛选参数按位透传core含category与tagId() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.list("取数", 2, 3, 1L, "data", 5L, "createdAt", "desc", 2, 10))
                .thenReturn(new PageView<>(List.of(), 0L));
        controller.list("取数", 2, 3, 1L, "data", 5L, "createdAt", "desc", 2, 10);
        verify(client).list("取数", 2, 3, 1L, "data", 5L, "createdAt", "desc", 2, 10);
    }

    @Test
    void 按项目筛选列表_非成员403不打core() {
        login(42L, "TESTER");
        projectClient.crossProject(1L);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.list(null, null, null, 1L, null, null, null, null, 1, 20));
        assertEquals(40301, ex.getCode());
        verify(client, never()).list(any(), any(), any(), any(), any(), any(), any(), any(),
                anyInt(), anyInt());
    }


    @Test
    void 新建系统级工具_非ADMIN403人话() {
        login(42L, "TEST_LEAD");
        ToolCreateRequest request = new ToolCreateRequest(null, "系统级工具", 1, "规范", "描述",
                "正文", null, null, null, null, null, null, false);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.create(request));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话须点出角色要求: " + ex.getMessage());
        verify(client, never()).create(any());
    }

    @Test
    void 新建项目级工具_负责人通过且请求体按位透传() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(2);
        ToolCreateRequest request = new ToolCreateRequest(1L, "取数助手", 2, "data", "描述",
                null, "[\"回归\"]", "{\"type\":\"object\"}", null, 1,
                "{\"url\":\"http://x\"}", 30000, true);
        when(client.create(request)).thenReturn(tool(1L, 42L));
        Response<ToolView> resp = controller.create(request);
        assertEquals("取数助手", resp.getData().name());
        verify(client).create(request);
    }


    @Test
    void 项目级工具详情_项目成员可读_非成员403() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        assertDoesNotThrow(() -> controller.get(9L));
        projectClient.crossProject(1L);
        assertThrows(ServiceException.class, () -> controller.get(9L));
    }

    @Test
    void 系统级工具详情_登录即可读() {
        login(42L, "TESTER");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        assertDoesNotThrow(() -> controller.get(9L));
    }


    @Test
    void 编辑先查得projectId判权_越权403不打update() {
        login(42L, "TESTER");
        projectClient.crossProject(1L);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        ToolUpdateRequest request = new ToolUpdateRequest(3, "改名", null, null, null, null,
                null, null, null, null, null);
        assertThrows(ServiceException.class, () -> controller.update(9L, request));
        verify(client, never()).update(anyLong(), any());
    }

    @Test
    void 编辑_ADMIN对系统级工具放行透传() {
        login(99L, "ADMIN");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        ToolUpdateRequest request = new ToolUpdateRequest(3, "改名", null, null, null, null,
                null, null, null, null, null);
        when(client.update(9L, request)).thenReturn(tool(null, 42L));
        assertDoesNotThrow(() -> controller.update(9L, request));
        verify(client).update(9L, request);
    }


    @Test
    void 删除_乐观锁query参透传core() {
        login(99L, "ADMIN");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        controller.delete(9L, 3);
        verify(client).delete(9L, 3);
    }


    @Test
    void 提交审批走管理面守卫_项目级负责人放行() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(2);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        when(client.submit(9L, 3)).thenReturn(tool(1L, 42L));
        assertDoesNotThrow(() -> controller.submit(9L, 3));
        verify(client).submit(9L, 3);
    }

    @Test
    void 审批通过_非管理面403不打core() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        assertThrows(ServiceException.class, () -> controller.approve(9L, 3));
        verify(client, never()).approve(anyLong(), anyInt());
    }

    @Test
    void 审批拒绝_原因body透传core() {
        login(99L, "ADMIN");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        ToolRejectRequest request = new ToolRejectRequest(3, "schema 不完整");
        when(client.reject(9L, request)).thenReturn(tool(null, 42L));
        controller.reject(9L, request);
        verify(client).reject(9L, request);
    }

    @Test
    void 下架走管理面守卫() {
        login(42L, "TESTER");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        assertThrows(ServiceException.class, () -> controller.retire(9L, 3));
        verify(client, never()).retire(anyLong(), anyInt());
    }


    @Test
    void 工具视图接收core二十五字段禁静默丢() throws Exception {
        String coreJson = """
                {"id":9,"projectId":null,"name":"取数助手","type":2,"typeLabel":"可执行脚本",
                 "category":"data","description":"描述","bodyPreview":null,
                 "tagsJson":"[\\"回归\\"]","inputSchemaJson":"{\\"type\\":\\"object\\"}",
                 "outputSchemaJson":null,"execKind":1,"execKindLabel":"HTTP回调",
                 "execSpecJson":"{\\"url\\":\\"http://x\\"}","timeoutMs":30000,
                 "status":3,"statusLabel":"已发布","currentVersion":2,"version":4,
                 "createdBy":42,"approvedBy":99,"approvedAt":"2026-09-10 02:00",
                 "rejectReason":null,"createdAt":"2026-09-10 01:00","updatedAt":"2026-09-10 02:00"}""";
        ToolView view = mapper.readValue(coreJson, ToolView.class);
        assertEquals(2, view.type());
        assertEquals("可执行脚本", view.typeLabel());
        assertEquals(1, view.execKind());
        assertEquals("HTTP回调", view.execKindLabel());
        assertEquals("{\"url\":\"http://x\"}", view.execSpecJson());
        assertEquals(30000, view.timeoutMs());
        assertEquals(3, view.status());
        assertEquals("已发布", view.statusLabel());
        assertEquals(2, view.currentVersion());
        assertEquals(4, view.version());
        assertEquals(99L, view.approvedBy());
        assertEquals("2026-09-10 02:00", view.approvedAt());
        assertEquals("[\"回归\"]", view.tagsJson());
    }

    @Test
    void 新建请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ToolCreateRequest(1L, "n", 2, "c", "d",
                "b", "t", "i", "o", 1, "e", 30000, true));
        for (String key : List.of("projectId", "name", "type", "category", "description", "body",
                "tagsJson", "inputSchemaJson", "outputSchemaJson", "execKind", "execSpecJson",
                "timeoutMs", "submitNow")) {
            assertTrue(json.contains("\"" + key + "\""), "契约字段缺失: " + key);
        }
    }

    @Test
    void 更新请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ToolUpdateRequest(3, "n", "c", "d", "b",
                "t", "i", "o", 1, "e", 60000));
        for (String key : List.of("expectedVersion", "name", "category", "description", "body",
                "tagsJson", "inputSchemaJson", "outputSchemaJson", "execKind", "execSpecJson",
                "timeoutMs")) {
            assertTrue(json.contains("\"" + key + "\""), "契约字段缺失: " + key);
        }
    }


    @Test
    void 调用历史_ADMIN放行分页时间参按位透传() {
        login(99L, "ADMIN");
        when(client.invocations(9L, "2026-09-10T00:00", null, 2, 10))
                .thenReturn(new PageView<>(List.of(), 0L));
        controller.invocations(9L, "2026-09-10T00:00", null, 2, 10);
        verify(client).invocations(9L, "2026-09-10T00:00", null, 2, 10);
    }

    @Test
    void 调用历史_非ADMIN403人话不打core() {
        login(42L, "TESTER");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.invocations(9L, null, null, 1, 20));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话须点出角色要求: " + ex.getMessage());
        verify(client, never()).invocations(anyLong(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void 调用历史视图接收core十二字段禁静默丢() throws Exception {
        String coreJson = """
                {"id":5,"caller":"user","actorId":42,"taskId":null,"toolId":9,"serverId":null,\
                "toolName":"取数助手","success":0,"durationMs":1234,\
                "error":"HTTP 502 网关错误","digestHash":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",\
                "createdAt":"2026-09-11 00:00:00"}""";
        ToolInvocationView view = mapper.readValue(coreJson, ToolInvocationView.class);
        assertEquals(5L, view.id());
        assertEquals("user", view.caller());
        assertEquals(42L, view.actorId());
        assertEquals(9L, view.toolId());
        assertEquals("取数助手", view.toolName());
        assertEquals(0, view.success());
        assertEquals(1234L, view.durationMs());
        assertEquals("HTTP 502 网关错误", view.error());
        assertEquals(64, view.digestHash().length());
        assertEquals("2026-09-11 00:00:00", view.createdAt());
    }


    @Test
    void 试跑_ADMIN放行样例入参按位透传结果回显() {
        login(99L, "ADMIN");
        ToolTestRunInput input = new ToolTestRunInput("{\"date\":\"2026-09-11\"}");
        when(client.testRun(9L, input)).thenReturn(
                new ToolTestRunResult(9L, "取数助手", true, "{\"ok\":true}", null, null, null, 120L));
        Response<ToolTestRunResult> resp = controller.testRun(9L, input);
        assertTrue(resp.getData().success());
        assertEquals("{\"ok\":true}", resp.getData().output());
        assertEquals(120L, resp.getData().durationMs());
        verify(client).testRun(9L, input);
    }

    @Test
    void 试跑_执行失败不抛5xx原样透传分类人话() {
        login(99L, "ADMIN");
        ToolTestRunInput input = new ToolTestRunInput(null);
        when(client.testRun(9L, input)).thenReturn(
                new ToolTestRunResult(9L, "取数助手", false, null, "timeout", 50401,
                        "目标接口未在 30000 毫秒内响应，请检查工具执行配置", 30001L));
        ToolTestRunResult result = controller.testRun(9L, input).getData();
        assertEquals(false, result.success());
        assertEquals("timeout", result.errorCategory());
        assertEquals(50401, result.errorCode());
        assertTrue(result.errorMessage().contains("30000"), "人话须含预算毫秒");
    }

    @Test
    void 试跑_空请求体透传null入参core() {
        login(99L, "ADMIN");
        when(client.testRun(eq(9L), isNull())).thenReturn(
                new ToolTestRunResult(9L, "取数助手", true, "pong", null, null, null, 5L));
        assertDoesNotThrow(() -> controller.testRun(9L, null));
        verify(client).testRun(9L, null);
    }

    @Test
    void 试跑_非ADMIN403人话不打core() {
        login(42L, "TEST_LEAD");
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.testRun(9L, new ToolTestRunInput("{}")));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话须点出角色要求: " + ex.getMessage());
        verify(client, never()).testRun(anyLong(), any());
    }

    @Test
    void 试跑结果视图接收core八字段禁静默丢() throws Exception {
        String coreJson = """
                {"toolId":9,"toolName":"取数助手","success":false,"output":null,\
                "errorCategory":"format","errorCode":null,\
                "errorMessage":"目标接口返回不是合法 JSON，原文已截断留痕","durationMs":88}""";
        ToolTestRunResult view = mapper.readValue(coreJson, ToolTestRunResult.class);
        assertEquals(9L, view.toolId());
        assertEquals("取数助手", view.toolName());
        assertEquals(false, view.success());
        assertEquals(null, view.output());
        assertEquals("format", view.errorCategory());
        assertEquals(null, view.errorCode());
        assertTrue(view.errorMessage().contains("JSON"));
        assertEquals(88L, view.durationMs());
    }

    @Test
    void 试跑请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ToolTestRunInput("x"));
        assertTrue(json.contains("\"input\""), "契约字段缺失: input");
    }


    @Test
    void 签发确认票_ADMIN放行票与TTL回显() {
        login(99L, "ADMIN");
        when(client.confirmTicket(9L)).thenReturn(new ToolConfirmTicketView("t7a".repeat(11), 300L));
        Response<ToolConfirmTicketView> resp = controller.confirmToken(9L);
        assertEquals(300L, resp.getData().expiresInSeconds());
        assertTrue(resp.getData().token().length() >= 32, "票随机串原样透传");
        verify(client).confirmTicket(9L);
    }

    @Test
    void 签发确认票_只读工具40001人话原样透传() {
        login(99L, "ADMIN");
        when(client.confirmTicket(9L)).thenThrow(
                new ServiceException("工具「取数助手」非写语义（无 x-write），无需确认票", 40001));
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.confirmToken(9L));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("无需确认票"), ex.getMessage());
    }

    @Test
    void 签发确认票_非ADMIN403人话不打core() {
        login(42L, "TEST_LEAD");
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.confirmToken(9L));
        assertEquals(40301, ex.getCode());
        assertTrue(ex.getMessage().contains("系统管理员"), () -> "人话须点出角色要求: " + ex.getMessage());
        verify(client, never()).confirmTicket(anyLong());
    }

    @Test
    void 携票执行_ADMIN三字段按位透传结果回显() {
        login(99L, "ADMIN");
        ToolInvokeRequest request = new ToolInvokeRequest(null, "{\"date\":\"2026-09-11\"}", "tk-1");
        when(client.invoke(9L, request)).thenReturn(
                new ToolTestRunResult(9L, "取数助手", true, "{\"ok\":true}", null, null, null, 120L));
        Response<ToolTestRunResult> resp = controller.invoke(9L, request);
        assertTrue(resp.getData().success());
        verify(client).invoke(9L, request);
    }

    @Test
    void 携票执行_未确认40001人话原样透传() {
        login(99L, "ADMIN");
        when(client.invoke(eq(9L), any())).thenThrow(new ServiceException(
                "工具「取数助手」具有写语义（x-write），需人工确认后执行——请先申请确认票", 40001));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.invoke(9L, new ToolInvokeRequest(null, "{}", null)));
        assertEquals(40001, ex.getCode());
        assertTrue(ex.getMessage().contains("人工确认"), ex.getMessage());
    }

    @Test
    void 携票执行_非ADMIN403不打core() {
        login(42L, "TESTER");
        assertThrows(ServiceException.class,
                () -> controller.invoke(9L, new ToolInvokeRequest(null, "{}", "tk-1")));
        verify(client, never()).invoke(anyLong(), any());
    }

    @Test
    void invoke请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ToolInvokeRequest(7L, "x", "tk"));
        assertTrue(json.contains("\"taskId\""), "契约字段缺失: taskId");
        assertTrue(json.contains("\"input\""), "契约字段缺失: input");
        assertTrue(json.contains("\"confirmToken\""), "契约字段缺失: confirmToken");
    }

    @Test
    void 确认票视图接收core两字段禁静默丢() throws Exception {
        ToolConfirmTicketView view = mapper.readValue(
                "{\"token\":\"abc123\",\"expiresInSeconds\":300}", ToolConfirmTicketView.class);
        assertEquals("abc123", view.token());
        assertEquals(300L, view.expiresInSeconds());
    }


    @Test
    void 完整详情_项目成员可读_非成员403不打core() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        when(client.detail(9L)).thenReturn(new ToolDetailView(9L, 1L, "取数助手", 2, "可执行脚本",
                "data", "描述", "# 完整正文全量（断链修复①）", null, null, null,
                null, null, null, 30000, 3, "已发布", 2, 4, 42L, null, null, null, null, null));
        Response<ToolDetailView> resp = controller.detail(9L);
        assertEquals("# 完整正文全量（断链修复①）", resp.getData().body(),
                "detail 链路=全量 body，区别于 /{id} 的 bodyPreview 截断");
        projectClient.crossProject(1L);
        assertThrows(ServiceException.class, () -> controller.detail(9L));
        verify(client, org.mockito.Mockito.times(1)).detail(9L);
    }

    @Test
    void 完整详情_系统级工具登录即可读() {
        login(42L, "TESTER");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        when(client.detail(9L)).thenReturn(new ToolDetailView(9L, null, "n", 1, "文档", null,
                null, "body", null, null, null, null, null, null, 30000, 1, "草稿", 1, 1,
                42L, null, null, null, null, null));
        assertDoesNotThrow(() -> controller.detail(9L));
    }

    @Test
    void 版本历史分页参按位透传_成员可读非成员403() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        when(client.versions(9L, 2, 10)).thenReturn(new PageView<>(List.of(), 0L));
        controller.versions(9L, 2, 10);
        verify(client).versions(9L, 2, 10);
        projectClient.crossProject(1L);
        assertThrows(ServiceException.class, () -> controller.versions(9L, 1, 20));
        verify(client, org.mockito.Mockito.times(1)).versions(9L, 2, 10);
    }

    @Test
    void 单版快照查看_version路径参透传_成员可读() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        when(client.versionDetail(9L, 3)).thenReturn(new ToolVersionDetailView(9L, 3, "旧正文",
                null, null, null, 30000, 3, "已发布", "edit", 42L, "2026-09-10 01:00"));
        Response<ToolVersionDetailView> resp = controller.versionDetail(9L, 3);
        assertEquals(3, resp.getData().version());
        assertEquals("edit", resp.getData().reason());
    }

    @Test
    void 下载_裸字节流与ContentDisposition原样透传禁重组() {
        login(42L, "TESTER");
        projectClient.memberRoleResult(1);
        when(client.get(9L)).thenReturn(tool(1L, 42L));
        ResponseEntity<byte[]> coreResp = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "application/json")
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''%E5%8F%96%E6%95%B0-v2.tool-bundle.json")
                .body("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8));
        when(client.download(9L, 2)).thenReturn(coreResp);
        ResponseEntity<byte[]> out = controller.download(9L, 2);
        assertArrayEquals("{\"name\":\"x\"}".getBytes(StandardCharsets.UTF_8), out.getBody());
        assertEquals(coreResp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                out.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                "文件名 core 单点组装，桥接层重组=双实现漂移");
    }

    @Test
    void 下载_缺省版本null透传core取当前版() {
        login(42L, "TESTER");
        when(client.get(9L)).thenReturn(tool(null, 42L));
        when(client.download(eq(9L), isNull())).thenReturn(ResponseEntity.ok().body(new byte[0]));
        controller.download(9L, null);
        verify(client).download(9L, null);
    }

    @Test
    void 回滚_走管理面守卫body两参透传() {
        login(42L, "TESTER");
        ToolRollbackRequest request = new ToolRollbackRequest(1, 4);
        when(client.get(9L)).thenReturn(tool(null, 42L));
        assertThrows(ServiceException.class, () -> controller.rollback(9L, request));
        verify(client, never()).rollback(anyLong(), any());
        login(99L, "ADMIN");
        when(client.rollback(9L, request)).thenReturn(tool(null, 42L));
        Response<ToolView> resp = controller.rollback(9L, request);
        assertEquals(9L, resp.getData().id());
        verify(client).rollback(9L, request);
    }

    @Test
    void 回滚请求序列化字段名与core契约同名() throws Exception {
        String json = mapper.writeValueAsString(new ToolRollbackRequest(1, 4));
        assertTrue(json.contains("\"version\""), "契约字段缺失: version");
        assertTrue(json.contains("\"expectedVersion\""), "契约字段缺失: expectedVersion");
    }

    @Test
    void 详情视图接收core二十五字段禁静默丢() throws Exception {
        String coreJson = """
                {"id":9,"projectId":1,"name":"取数助手","type":2,"typeLabel":"可执行脚本",
                 "category":"data","description":"描述","body":"# 完整正文",
                 "tagsJson":"[\\"回归\\"]","inputSchemaJson":"{\\"type\\":\\"object\\"}",
                 "outputSchemaJson":null,"execKind":1,"execKindLabel":"HTTP回调",
                 "execSpecJson":"{\\"url\\":\\"http://x\\"}","timeoutMs":30000,
                 "status":3,"statusLabel":"已发布","currentVersion":2,"version":4,
                 "createdBy":42,"approvedBy":99,"approvedAt":"2026-09-10 02:00",
                 "rejectReason":null,"createdAt":"2026-09-10 01:00","updatedAt":"2026-09-10 02:00"}""";
        ToolDetailView view = mapper.readValue(coreJson, ToolDetailView.class);
        assertEquals("# 完整正文", view.body());
        assertEquals(25, ToolDetailView.class.getRecordComponents().length,
                "与 core ToolDetail 字段数漂移");
        assertEquals(99L, view.approvedBy());
        assertEquals(2, view.currentVersion());
    }

    @Test
    void 版本链条目视图接收core八字段禁静默丢() throws Exception {
        String coreJson = """
                {"toolId":9,"version":2,"reason":"edit","statusAtSnapshot":3,
                 "statusLabel":"已发布","createdBy":42,"createdAt":"2026-09-10 01:00",
                 "bodyPreview":"# 预览"}""";
        ToolVersionInfoView view = mapper.readValue(coreJson, ToolVersionInfoView.class);
        assertEquals(8, ToolVersionInfoView.class.getRecordComponents().length);
        assertEquals(2, view.version());
        assertEquals("edit", view.reason());
        assertEquals("# 预览", view.bodyPreview());
    }

    @Test
    void 版本快照视图接收core十二字段禁静默丢() throws Exception {
        String coreJson = """
                {"toolId":9,"version":1,"body":"旧版完整正文","inputSchemaJson":null,
                 "outputSchemaJson":null,"execSpecJson":null,"timeoutMs":30000,
                 "statusAtSnapshot":1,"statusLabel":"草稿","reason":"rollback-pre",
                 "createdBy":42,"createdAt":"2026-09-10 01:00"}""";
        ToolVersionDetailView view = mapper.readValue(coreJson, ToolVersionDetailView.class);
        assertEquals(12, ToolVersionDetailView.class.getRecordComponents().length);
        assertEquals("旧版完整正文", view.body());
        assertEquals("rollback-pre", view.reason());
    }
}
