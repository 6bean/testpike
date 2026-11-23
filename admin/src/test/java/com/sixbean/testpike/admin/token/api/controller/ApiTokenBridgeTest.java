package com.sixbean.testpike.admin.token.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreateRequest;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreatedView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenUsageView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenView;
import com.sixbean.testpike.admin.token.infrastructure.remote.client.ApiTokenClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class ApiTokenBridgeTest {

    private final ApiTokenClient client = mock(ApiTokenClient.class);
    private final ApiTokenController controller = new ApiTokenController(client);
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

    @Test
    void 列表以登录用户为属主透传core() {
        login(7L, "TESTER");
        when(client.listByUser(7L)).thenReturn(List.of());
        Response<List<ApiTokenView>> resp = controller.list();
        assertEquals("0", resp.getCode());
        verify(client).listByUser(7L);
    }

    @Test
    void 创建属主取登录态_请求体原样透传_一次性明文完整返回() {
        login(7L, "TESTER");
        ApiTokenCreateRequest request = new ApiTokenCreateRequest(
                "ci-bot", "mcp.case", LocalDateTime.of(2099, 1, 1, 0, 0));
        when(client.create(eq(7L), any(ApiTokenCreateRequest.class)))
                .thenReturn(new ApiTokenCreatedView(3L, "tp_pat_xxx-one-time-plaintext"));

        Response<ApiTokenCreatedView> resp = controller.create(request);

        verify(client).create(7L, request);
        assertEquals("tp_pat_xxx-one-time-plaintext", resp.getData().plaintext(),
                "明文仅创建响应透传一次（02/D5）");
        assertEquals(3L, resp.getData().id());
    }

    @Test
    void 吊销透传token主键与操作人_本人判定在core() {
        login(7L, "TESTER");
        controller.revoke(5L);
        verify(client).revoke(5L, 7L);
    }

    @Test
    void 桥层零鉴权旁路_未登录必401不触core() {
        assertEquals(40101, assertThrows401());
        verifyNoInteractions(client);
    }

    private int assertThrows401() {
        try {
            controller.list();
            throw new AssertionError("未登录必须 401，不得放行");
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            return e.getCode();
        }
    }

    /** GA3 usage read path: token id and logged-in operator pass straight to core; the owner ∪ admin read-through check lives only in core (bridge re-checks nothing). */
    @Test
    void 用量读链路以登录操作人透传_归属判定在core() {
        login(7L, "TESTER");
        when(client.usage(42L, 7L)).thenReturn(new ApiTokenUsageView(42L, 128L, 3L, 233.0));

        Response<ApiTokenUsageView> resp = controller.usage(42L);

        verify(client).usage(42L, 7L);
        assertEquals(42L, resp.getData().tokenId());
        assertEquals(128L, resp.getData().calls());
        assertEquals(3L, resp.getData().failures());
        assertEquals(233.0, resp.getData().p95DurationMs());
    }

    /** Same path without a login must fail with 40101 before touching core (auth gate stays in front of the bridge). */
    @Test
    void 用量读链路未登录必401不触core() {
        try {
            controller.usage(42L);
            throw new AssertionError("未登录必须 401，不得放行");
        } catch (com.sixbean.testpike.common.exception.ServiceException e) {
            assertEquals(40101, e.getCode());
        }
        verifyNoInteractions(client);
    }

    /** core view JSON aligns field-by-field with the admin view; a null p95 (no latency sample) is passed through, never faked as 0. */
    @Test
    void core用量视图JSON对齐_p95空值直通不造假() throws Exception {
        String coreJson = "{\"tokenId\":9,\"calls\":0,\"failures\":0,\"p95DurationMs\":null}";
        ApiTokenUsageView view = mapper.readValue(coreJson, ApiTokenUsageView.class);
        assertEquals(9L, view.tokenId());
        assertNull(view.p95DurationMs(), "无延迟样本=null 透传，桥层禁造假 0");
    }

    @Test
    void core视图JSON字段对齐_指纹与时间零静默丢() throws Exception {
        String coreJson = """
                {"id":3,"name":"ci-bot","scopes":"mcp.case,mcp.read","fingerprint":"ab12cd34",
                 "revoked":0,"lastUsedAt":"2026-09-10 10:00:00","expiresAt":null}""";
        ApiTokenView view = mapper.readValue(coreJson, ApiTokenView.class);
        assertEquals("ab12cd34", view.fingerprint(), "前 8 位指纹禁静默丢（02 哈希不外泄口径）");
        assertEquals("2026-09-10 10:00:00", view.lastUsedAt());
        assertNull(view.expiresAt(), "永久 Token expiresAt=null");
        assertEquals("mcp.case,mcp.read", view.scopes());
    }

    /**
     * GA1 piece A bridge contract: the project pin rides the create request
     * untouched, and the core list-view JSON maps projectScope onto the admin
     * view field-for-field (by-name deserialization — a silent drop here means
     * the Token page shows no pin column at all).
     */
    @Test
    void createForwardsProjectPinAndCoreListViewAlignsIt() throws Exception {
        login(7L, "TESTER");
        ApiTokenCreateRequest request = new ApiTokenCreateRequest(
                "pin-bot", "mcp.case", null, "3,12");
        when(client.create(eq(7L), any(ApiTokenCreateRequest.class)))
                .thenReturn(new ApiTokenCreatedView(9L, "tp_pat_one-time-plaintext"));

        controller.create(request);

        verify(client).create(7L, request);

        String coreJson = "[{\"id\":9,\"name\":\"pin-bot\",\"scopes\":\"mcp.case\","
                + "\"fingerprint\":\"abcdef01\",\"revoked\":0,\"lastUsedAt\":null,"
                + "\"expiresAt\":null,\"projectScope\":\"3,12\"}]";
        List<ApiTokenView> views = mapper.readValue(coreJson,
                new com.fasterxml.jackson.core.type.TypeReference<List<ApiTokenView>>() { });
        assertEquals("3,12", views.get(0).projectScope(),
                "core projectScope must map onto the admin view (no silent field loss)");
    }
}
