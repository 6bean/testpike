package com.sixbean.testpike.admin.reportcenter.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterDetailView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterGenerateInput;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterListItemView;
import com.sixbean.testpike.admin.reportcenter.api.dto.ReportCenterQueryInput;
import com.sixbean.testpike.admin.reportcenter.app.service.impl.ReportCenterAdminServiceImpl;
import com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client.CoreReportCenterClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ReportCenterBridgeTest {

    private final CoreReportCenterClient client = mock(CoreReportCenterClient.class);
    private final StubCoreProjectClient stub = new StubCoreProjectClient();
    private final ReportCenterController controller = new ReportCenterController(
            new ReportCenterAdminServiceImpl(client, new ProjectAccessGuard(stub)));

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private static CurrentUser login(long userId, String... roles) {
        CurrentUser user = new CurrentUser(userId, "u" + userId, "U", "D", List.of(roles),
                System.currentTimeMillis() + 3600_000, null);
        UserContext.set(user);
        return user;
    }

    private static ReportCenterQueryInput query(Long scopeId, Integer scopeType) {
        return new ReportCenterQueryInput(1, 20, null, scopeType, scopeId, null, null);
    }

    private static ReportCenterDetailView projectRow() {
        return new ReportCenterDetailView(5L, 1, 3L, 1, null, null, "{}", null, 7L);
    }

    private static ReportCenterDetailView globalRow() {
        return new ReportCenterDetailView(6L, 2, 0L, 1, null, null, "{}", null, 0L);
    }

    /** Program-scope row (scope_type=3, scope_id=program id); program ids live in a
     *  different id domain than projects, so project membership checks cannot apply. */
    private static ReportCenterDetailView programRow() {
        return new ReportCenterDetailView(7L, 3, 4242L, 1, null, null, "{}", null, 0L);
    }


    @Test
    void 全局列表_非ADMIN403_不触core() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.list(1, 20, null, null, null, null, null, null, null, 0L));
        assertEquals(40301, ex.getCode(), "跨项目报表列表仅系统管理员（02 §2.18）");
        verifyNoInteractions(client);
    }

    @Test
    void 列表_项目过滤成员通过_QUERY透传() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        when(client.list(eq(7L), any(ReportCenterQueryInput.class)))
                .thenReturn(new PageView<>(List.of(), 0L));
        Response<PageView<ReportCenterListItemView>> resp =
                controller.list(2, 50, 1, 1, 3L, "2026-09-01T00:00", "2026-09-08T00:00", null, null, 0L);
        assertEquals("0", resp.getCode());
        verify(client).list(7L, new ReportCenterQueryInput(2, 50, 1, 1, 3L,
                "2026-09-01T00:00", "2026-09-08T00:00"));
    }

    @Test
    void 列表_项目过滤非成员403_不触core() {
        login(7L, "TESTER");
        stub.memberRoleResult(0);
        assertThrows(ServiceException.class,
                () -> controller.list(1, 20, null, null, 3L, null, null, null, null, 0L));
        verifyNoInteractions(client);
    }


    @Test
    void 详情_项目维行成员通过_非成员403() {
        login(7L, "TESTER");
        when(client.detail(5L)).thenReturn(projectRow());
        stub.memberRoleResult(0);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.detail(5L));
        assertEquals(40301, ex.getCode(), "非该项目成员不可看项目维快照（H1 隔离）");

        stub.memberRoleResult(1);
        assertEquals("0", controller.detail(5L).getCode());
    }

    @Test
    void 详情_全局维行_仅ADMIN_TEST_LEAD也拒() {
        login(7L, "TEST_LEAD");
        when(client.detail(6L)).thenReturn(globalRow());
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.detail(6L));
        assertEquals(40301, ex.getCode(), "全局维快照仅系统管理员（02 §2.18）");
    }

    @Test
    void 详情_项目集维行_非ADMIN拒且ADMIN通过() {
        // Program-scope snapshots aggregate across projects; the program id is not a
        // project id, so membership fallback would silently grant the wrong people.
        login(7L, "TEST_LEAD");
        when(client.detail(7L)).thenReturn(programRow());
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.detail(7L));
        assertEquals(40301, ex.getCode(), "项目集维快照仅系统管理员可查看");

        login(7L, "ADMIN");
        assertEquals("0", controller.detail(7L).getCode(), "系统管理员可读项目集维快照");
    }

    @Test
    void 导出_项目集维行越权不触core导出链路() {
        login(7L, "TESTER");
        when(client.detail(7L)).thenReturn(programRow());
        assertThrows(ServiceException.class, () -> controller.export(7L, "csv", null));
        verify(client, never()).export(anyLong(), anyLong(), anyString(), any());
    }


    @Test
    void 导出_越权不触core导出链路() {
        login(7L, "TESTER");
        when(client.detail(5L)).thenReturn(projectRow());
        stub.memberRoleResult(0);
        assertThrows(ServiceException.class, () -> controller.export(5L, "csv", null));
        verify(client, never()).export(anyLong(), anyLong(), anyString(), any());
    }

    @Test
    void 导出_字节流与ContentDisposition原样透传_禁重组文件名() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        when(client.detail(5L)).thenReturn(projectRow());
        ResponseEntity<byte[]> coreResp = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, "text/csv;charset=UTF-8")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename*=UTF-8''%E5%91%A8%E6%8A%A5.csv")
                .body("a,b".getBytes(StandardCharsets.UTF_8));
        when(client.export(7L, 5L, "csv", "deep")).thenReturn(coreResp);
        ResponseEntity<byte[]> out = controller.export(5L, "csv", "deep");
        assertArrayEquals("a,b".getBytes(StandardCharsets.UTF_8), out.getBody());
        assertEquals(coreResp.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                out.getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION),
                "文件名 core 单点组装，桥接层重组=双实现漂移（断点坑⑧）");
    }

    @Test
    void 导出_core400人话JSON状态原样透传() {
        login(7L, "ADMIN");
        ResponseEntity<byte[]> coreResp = ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"code\":\"40001\",\"msg\":\"format 仅支持 csv 或 pdf\"}".getBytes(StandardCharsets.UTF_8));
        when(client.detail(5L)).thenReturn(globalRow());
        when(client.export(7L, 5L, "xls", null)).thenReturn(coreResp);
        ResponseEntity<byte[]> out = controller.export(5L, "xls", null);
        assertEquals(HttpStatus.BAD_REQUEST, out.getStatusCode());
        assertTrue(new String(out.getBody(), StandardCharsets.UTF_8).contains("40001"));
    }


    @Test
    void 生成_非ADMIN403_不触core() {
        login(7L, "TEST_LEAD");
        ReportCenterGenerateInput input = new ReportCenterGenerateInput(1, 1, 3L, null, null);
        ServiceException ex = assertThrows(ServiceException.class, () -> controller.generate(input));
        assertEquals(40301, ex.getCode(), "手动补生成=治理动作仅 ADMIN（02 §2.18）");
        verifyNoInteractions(client);
    }

    @Test
    void 生成_ADMIN_操作人与入参透传_admin零值域判定() {
        login(7L, "ADMIN");
        ReportCenterGenerateInput input = new ReportCenterGenerateInput(9, 2, null, "bad", "worse");
        when(client.generate(eq(7L), any(ReportCenterGenerateInput.class))).thenReturn(globalRow());
        Response<ReportCenterDetailView> resp = controller.generate(input);
        assertEquals("0", resp.getCode());
        verify(client).generate(7L, input);
    }


    @Test
    void core视图JSON字段对齐_列表行与详情payload零静默丢() throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String listItemJson = """
                {"id":5,"scopeType":1,"scopeId":3,"reportType":1,
                 "periodStart":"2026-09-07T00:00:00","periodEnd":"2026-09-14T00:00:00",
                 "generatedAt":"2026-09-14T06:30:00","createdBy":0}""";
        ReportCenterListItemView item =
                mapper.readValue(listItemJson, ReportCenterListItemView.class);
        assertEquals(5L, item.id());
        assertEquals(0L, item.createdBy(), "定时生成=0 系统账号语义透传");
        assertEquals("2026-09-07T00:00", item.periodStart().toString());

        String viewJson = """
                {"id":6,"scopeType":2,"scopeId":0,"reportType":2,
                 "periodStart":"2026-08-01T00:00:00","periodEnd":"2026-09-01T00:00:00",
                 "payloadJson":"{\\"pass-rate\\":{\\"v\\":\\"1.0\\",\\"value\\":0.5}}",
                 "generatedAt":"2026-09-01T06:30:00","createdBy":1}""";
        ReportCenterDetailView view = mapper.readValue(viewJson, ReportCenterDetailView.class);
        assertEquals(0L, view.scopeId(), "全局维 scope_id=0（V53 裁定②）禁丢成 null");
        assertTrue(view.payloadJson().contains("\"v\":\"1.0\""),
                "payload 版本内嵌原文回读（TC-O10-02 快照不失真）");
    }

    @Test
    void 桥接URI组装_scopeId为null省略参数_空串不误传() {
        ReportCenterQueryInput q = new ReportCenterQueryInput(1, 20, null, null, null, null, null);
        String url = com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client
                .RestCoreReportCenterClient.buildListUri(q);
        assertEquals("/internal/report-center?page=1&size=20", url);
        // The 7-arg legacy construction stays valid (delegates with the null sort pair)
        // and still emits no sortBy/sortDir parameters — the zero-drift contract.
        assertEquals("/internal/report-center?page=1&size=20",
                com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client
                        .RestCoreReportCenterClient.buildListUri(
                                new ReportCenterQueryInput(1, 20, null, null, null, null, null, null, null)));
    }

    // ── Sort pair forwarding : the bridge passes the
    // optional pair through untouched — no second gate, no empty-string params ──

    @Test
    void 列表排序键对透传到core入参与URI() {
        login(7L, "ADMIN");
        when(client.list(eq(7L), any(ReportCenterQueryInput.class)))
                .thenReturn(new PageView<>(List.of(), 0L));
        controller.list(1, 20, null, null, 3L, null, null, "generated_at", "asc", 0L);
        verify(client).list(7L, new ReportCenterQueryInput(1, 20, null, null, 3L, null, null,
                "generated_at", "asc"));
        String url = com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client
                .RestCoreReportCenterClient.buildListUri(new ReportCenterQueryInput(
                        1, 20, null, null, null, null, null, "period_start", "desc"));
        assertEquals("/internal/report-center?page=1&size=20&sortBy=period_start&sortDir=desc", url);
        // blank pair = omitted (never sortBy= empty reaching the core parser)
        assertEquals("/internal/report-center?page=1&size=20",
                com.sixbean.testpike.admin.reportcenter.infrastructure.remote.client
                        .RestCoreReportCenterClient.buildListUri(new ReportCenterQueryInput(
                                1, 20, null, null, null, null, null, "", "")));
    }
}
