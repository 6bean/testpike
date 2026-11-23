package com.sixbean.testpike.admin.spec.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.LoggedUserFixture;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.spec.api.dto.ApiEndpointView;
import com.sixbean.testpike.admin.spec.api.dto.ApiParameterView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecView;
import com.sixbean.testpike.admin.spec.api.dto.SpecDiffView;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.admin.spec.api.dto.SpecSubscriptionView;
import com.sixbean.testpike.admin.spec.api.dto.SpecUrlFetchRequest;
import com.sixbean.testpike.admin.spec.infrastructure.remote.client.CoreApiSpecClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class ApiSpecControllerTest {

    private final CoreApiSpecClient client = mock(CoreApiSpecClient.class);
    private final ProjectAccessGuard guard = mock(ProjectAccessGuard.class);
    private final ApiSpecController controller = new ApiSpecController(client, guard);

    @AfterEach
    void tearDown() {
        LoggedUserFixture.logout();
    }

    private static ApiSpecView specRow(long id, long projectId) {
        return new ApiSpecView(id, projectId, null, "订单中心契约", "openapi3-json",
                "Order API", "a".repeat(64), 2,
                List.of(new ApiEndpointView("POST", "/orders", "POST /orders", "createOrder",
                        "创建订单",
                        List.of(new ApiParameterView("idempotencyKey", "header", true, "string")),
                        "application/json", "CreateOrderRequest",
                        java.util.Map.of("200", "OrderVO"), List.of("apiKeyAuth"), "b".repeat(64))),
                11L, new SpecDiffView(List.of("GET /orders/{id}"), List.of("POST /orders"),
                        List.of("DELETE /orders/{id}")),
                7L, "2026-09-27 12:00:00");
    }

    @Test
    void 上传写路径_需求写闸先过_契约名与字节原样进桥() throws IOException {
        LoggedUserFixture.loginAsTestLead(7L);
        MockMultipartFile file = new MockMultipartFile("file", "order-api.json",
                "application/json", "{\"openapi\":\"3.0.1\"}".getBytes(StandardCharsets.UTF_8));
        when(client.upload(eq(42L), eq("订单中心契约"), any(byte[].class)))
                .thenReturn(specRow(12L, 42L));
        Response<ApiSpecView> resp = controller.upload(42L, "订单中心契约", file);
        assertEquals("0", resp.getCode());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanWriteRequirement(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿路径参数冒充）");
        ArgumentCaptor<byte[]> bytes = ArgumentCaptor.forClass(byte[].class);
        verify(client).upload(eq(42L), eq("订单中心契约"), bytes.capture());
        assertEquals("{\"openapi\":\"3.0.1\"}",
                new String(bytes.getValue(), StandardCharsets.UTF_8), "字节禁二次加工");
    }

    @Test
    void 列表读路径_成员闸先过_分页参数按位透传() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.list(3L, 2, 20)).thenReturn(new PageView<>(List.of(specRow(12L, 3L)), 1L));
        Response<PageView<ApiSpecView>> resp = controller.list(3L, 2, 20);
        assertEquals("0", resp.getCode());
        assertEquals(1, resp.getData().list().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(3L));
        verify(client).list(3L, 2, 20);
    }

    @Test
    void 详情读路径_先取行后按行归属验权_越权项目成员拒() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(12L)).thenReturn(specRow(12L, 42L));
        Response<ApiSpecView> resp = controller.get(12L);
        assertEquals("0", resp.getCode());
        verify(guard).assertCanView(any(CurrentUser.class), eq(42L));
    }

    @Test
    void 详情不存在_core404人话在验权前透传_不打验权链路() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.get(99L)).thenThrow(new ServiceException("契约版本不存在: 99",
                BizErrorCode.NOT_FOUND));
        assertThrows(ServiceException.class, () -> controller.get(99L));
        verify(guard, never()).assertCanView(any(), eq(42L));
    }

    @Test
    void 版本链读路径_成员闸先过_契约名原样透传桥层编码() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.history(3L, "订单中心契约")).thenReturn(List.of(specRow(11L, 3L), specRow(12L, 3L)));
        Response<List<ApiSpecView>> resp = controller.history(3L, "订单中心契约");
        assertEquals("0", resp.getCode());
        assertEquals(2, resp.getData().size());
        verify(guard).assertCanView(any(CurrentUser.class), eq(3L));
        verify(client).history(3L, "订单中心契约");
    }

    @Test
    void URL拉取写路径_需求写闸先过_项目名与地址原样进桥() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.fetchFromUrl(42L, "订单中心契约", "http://api.local/openapi.json"))
                .thenReturn(specRow(13L, 42L));
        Response<ApiSpecView> resp = controller.fetchUrl(
                new SpecUrlFetchRequest(42L, "订单中心契约", "http://api.local/openapi.json"));
        assertEquals("0", resp.getCode());
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanWriteRequirement(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId(), "守卫必须用当前登录人（禁拿请求体参数冒充）");
        verify(client).fetchFromUrl(42L, "订单中心契约", "http://api.local/openapi.json");
        assertEquals(13L, resp.getData().id(), "返回=core 落库版本行原样透传，admin 禁二次加工");
    }

    @Test
    void URL拉取_core400人话透传_验权先行禁吞错误码() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.fetchFromUrl(3L, "n", "ftp://bad/x"))
                .thenThrow(new ServiceException("订阅地址仅支持 http/https 链接，当前=ftp",
                        BizErrorCode.VALIDATION_ERROR));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.fetchUrl(new SpecUrlFetchRequest(3L, "n", "ftp://bad/x")));
        assertTrue(ex.getMessage().contains("http/https"), "core 人话消息禁改写");
        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(3L));
    }

    @Test
    void 视图字段按名镜像core_清单与diff三分类不静默丢() {
        ApiSpecView view = specRow(12L, 42L);
        assertEquals(2, view.specVersion(), "版本号源=core 版本链，admin 禁二次计算");
        ApiEndpointView endpoint = view.endpoints().get(0);
        assertEquals("POST /orders", endpoint.endpointKey(), "diff 对照键按名镜像");
        assertEquals(List.of("apiKeyAuth"), endpoint.securityRefs(), "安全键只透传不解释");
        assertEquals("idempotencyKey", endpoint.parameters().get(0).name());
        assertEquals(List.of("GET /orders/{id}"), view.diffSummary().added());
        assertEquals("2026-09-27 12:00:00", view.createdAt(), "时间格式化口径=core ViewFormats 单点");
        assertTrue(view.documentId() == null, "本期独立上传链路=文档关联恒空");
    }

    // ---------- set-internal contract subscription (cross-project reference) ----------

    @Test
    void 订阅新建_集内引用源字段原样进桥_写闸按订阅方项目() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.createSubscription(42L, "宠物契约镜像", null, 30,
                "program_spec", 8L, "宠物中心契约"))
                .thenReturn(subscriptionRow(21L, 42L, "program_spec", 8L, "宠物中心契约"));
        Response<SpecSubscriptionView> resp = controller.createSubscription(
                new SpecSubscriptionSaveRequest(42L, "宠物契约镜像", null, 30, null,
                        "program_spec", 8L, "宠物中心契约"));
        assertEquals("0", resp.getCode());
        verify(guard).assertCanWriteRequirement(any(CurrentUser.class), eq(42L));
        verify(client).createSubscription(42L, "宠物契约镜像", null, 30,
                "program_spec", 8L, "宠物中心契约");
        assertEquals("program_spec", resp.getData().sourceKind(), "源类型按名镜像 core 非 admin 二次加工");
        assertEquals(8L, resp.getData().providerProjectId(), "提供方项目 id 原样透传");
        assertEquals("宠物中心契约", resp.getData().providerSpecName(), "提供方契约名原样透传");
    }

    @Test
    void 订阅新建_URL源不传集内字段_桥零默认值注入() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.createSubscription(42L, "订单契约", "http://api.local/openapi.json", 60,
                null, null, null))
                .thenReturn(subscriptionRow(22L, 42L, "url", null, null));
        controller.createSubscription(new SpecSubscriptionSaveRequest(
                42L, "订单契约", "http://api.local/openapi.json", 60, null, null, null, null));
        verify(client).createSubscription(42L, "订单契约", "http://api.local/openapi.json", 60,
                null, null, null);
    }

    @Test
    void 订阅编辑_集内源字段进桥_写闸按行归属非前端传参() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.getSubscription(9L))
                .thenReturn(subscriptionRow(9L, 42L, "program_spec", 8L, "宠物中心契约"));
        when(client.updateSubscription(9L, "宠物契约镜像", null, 15, null,
                "program_spec", 11L, "库存中心契约"))
                .thenReturn(subscriptionRow(9L, 42L, "program_spec", 11L, "库存中心契约"));
        Response<SpecSubscriptionView> resp = controller.updateSubscription(9L,
                new SpecSubscriptionSaveRequest(999L, "宠物契约镜像", null, 15, null,
                        "program_spec", 11L, "库存中心契约"));
        ArgumentCaptor<CurrentUser> user = ArgumentCaptor.forClass(CurrentUser.class);
        verify(guard).assertCanWriteRequirement(user.capture(), eq(42L));
        assertEquals(7L, user.getValue().userId());
        verify(client).updateSubscription(9L, "宠物契约镜像", null, 15, null,
                "program_spec", 11L, "库存中心契约");
        assertEquals(11L, resp.getData().providerProjectId(), "换提供方=core 落库行透传");
    }

    @Test
    void 订阅列表_镜像字段随行返回_分页参数按位透传() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.listSubscriptions(42L, 2, 20)).thenReturn(new PageView<>(
                List.of(subscriptionRow(31L, 42L, "program_spec", 8L, "宠物中心契约")), 1L));
        Response<PageView<SpecSubscriptionView>> resp = controller.listSubscriptions(42L, 2, 20);
        verify(guard).assertCanView(any(CurrentUser.class), eq(42L));
        assertEquals(1, resp.getData().list().size());
        assertEquals("program_spec", resp.getData().list().get(0).sourceKind());
    }

    @Test
    void 订阅集内源_core四闸人话原样透传_不改写不降级() {
        LoggedUserFixture.loginAsTestLead(7L);
        when(client.createSubscription(42L, "x", null, 30, "program_spec", 42L, "n"))
                .thenThrow(new ServiceException("不能订阅本项目自身的契约", BizErrorCode.VALIDATION_ERROR));
        ServiceException self = assertThrows(ServiceException.class,
                () -> controller.createSubscription(new SpecSubscriptionSaveRequest(
                        42L, "x", null, 30, null, "program_spec", 42L, "n")));
        assertTrue(self.getMessage().contains("本项目自身"), "core 人话消息禁改写");

        when(client.createSubscription(42L, "y", null, 30, "program_spec", 8L, "n"))
                .thenThrow(new ServiceException("两个项目不在同一项目集，无法跨项目引用契约",
                        BizErrorCode.VALIDATION_ERROR));
        ServiceException offProgram = assertThrows(ServiceException.class,
                () -> controller.createSubscription(new SpecSubscriptionSaveRequest(
                        42L, "y", null, 30, null, "program_spec", 8L, "n")));
        assertTrue(offProgram.getMessage().contains("项目集"), "护栏失败原因须点名项目集");
    }

    private static SpecSubscriptionView subscriptionRow(long id, long projectId, String sourceKind,
                                                         Long providerProjectId, String providerSpecName) {
        return new SpecSubscriptionView(id, projectId, "宠物契约镜像", null, 30, true,
                5L, "updated", null, "2026-11-02 02:00:00", 7L, "2026-11-02 02:00:00",
                sourceKind, providerProjectId, providerSpecName);
    }
}
