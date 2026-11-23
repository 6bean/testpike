package com.sixbean.testpike.admin.cost.api.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.admin.cost.app.service.impl.CostAdminServiceImpl;
import com.sixbean.testpike.admin.cost.infrastructure.remote.client.CoreCostClient;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.project.infrastructure.remote.client.StubCoreProjectClient;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class CostBridgeTest {

    private final CoreCostClient client = mock(CoreCostClient.class);
    private final com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient chat =
            mock(com.sixbean.testpike.admin.aichat.infrastructure.remote.client.CoreAiChatClient.class);
    private final StubCoreProjectClient stub = new StubCoreProjectClient();
    private final CostController controller =
            new CostController(new CostAdminServiceImpl(client, chat, new ProjectAccessGuard(stub)));
    private final ObjectMapper mapper = new ObjectMapper();

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

    private static CostOverviewView overviewFixture() {
        var item = new com.sixbean.testpike.admin.cost.api.dto.CostDimItem("总计", new BigDecimal("1.50"), 3);
        return new CostOverviewView("2026-09", null, item, List.of(), List.of(), List.of(), List.of());
    }

    @Test
    void 全局成本_仅ADMIN_非管理员403不触core() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.overview("2026-09", null));
        assertEquals(40301, ex.getCode(), "全局成本聚合仅系统管理员（02 §2.18 权限列）");
        verifyNoInteractions(client);
    }

    @Test
    void 会话归因_全局仅ADMIN_普通成员403不触下游() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.sessionUsage("2026-10", null, 1, 20));
        assertEquals(40301, ex.getCode(), "全局会话归因沿用 overview 同谱权限面（E9 T435 阶段3）");
        verifyNoInteractions(chat);
    }

    @Test
    void 会话归因_ADMIN全局_透传切片与分页参数() {
        login(7L, "ADMIN");
        var page = new com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView(
                List.of(), 0, 1, 20);
        when(chat.usageAttribution("2026-10", null, 1, 20)).thenReturn(page);
        Response<com.sixbean.testpike.admin.aichat.api.dto.ChatSessionUsagePageMirrorView> resp =
                controller.sessionUsage("2026-10", null, 1, 20);
        assertEquals("0", resp.getCode());
        verify(chat).usageAttribution("2026-10", null, 1, 20);
    }

    @Test
    void 全局成本_ADMIN_透传projectId为null() {
        login(7L, "ADMIN");
        when(client.overview(eq("2026-09"), isNull())).thenReturn(overviewFixture());
        Response<CostOverviewView> resp = controller.overview("2026-09", null);
        assertEquals("0", resp.getCode());
        verify(client).overview("2026-09", null);
    }

    @Test
    void 项目成本_普通成员403_负责人通过() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.overview("2026-09", 3L));
        assertEquals(40301, ex.getCode(), "项目成本=项目负责人（02 §2.18），普通成员 403");
        verifyNoInteractions(client);

        stub.memberRoleResult(2);
        when(client.overview("2026-09", 3L)).thenReturn(overviewFixture());
        assertEquals("0", controller.overview("2026-09", 3L).getCode());
        verify(client).overview("2026-09", 3L);
    }

    @Test
    void 预算读取_项目负责人_透传projectId() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        when(client.getBudget(3L)).thenReturn(new BudgetView(3L, 1000L, 80, true));
        Response<BudgetView> resp = controller.getBudget(3L);
        assertEquals(1000L, resp.getData().budgetCents());
        assertTrue(resp.getData().overridden(), "覆写态透传（false=跟随全局默认，预算块显示口径）");
        verify(client).getBudget(3L);
    }

    @Test
    void 预算写入_操作人与入参透传_admin零值域判定() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        BudgetInput input = new BudgetInput(-1L, 0);
        when(client.putBudget(eq(7L), eq(3L), any(BudgetInput.class)))
                .thenReturn(new BudgetView(3L, 0L, 80, false));
        Response<BudgetView> resp = controller.putBudget(3L, input);
        assertEquals("0", resp.getCode());
        verify(client).putBudget(7L, 3L, input);
    }

    @Test
    void 预算写入_非负责人403_不触core() {
        login(7L, "TESTER");
        stub.memberRoleResult(1);
        assertThrows(ServiceException.class,
                () -> controller.putBudget(3L, new BudgetInput(100L, 80)));
        verifyNoInteractions(client);
    }

    @Test
    void 熔断恢复_ADMIN_操作人与入参透传_admin零值域判定() {
        login(7L, "ADMIN");
        BreakOverrideInput input = new BreakOverrideInput("", 73);
        when(client.breakOverride(eq(7L), eq(3L), any(BreakOverrideInput.class)))
                .thenReturn(new BreakOverrideView(3L, "", 73, null));
        Response<BreakOverrideView> resp = controller.breakOverride(3L, input);
        assertEquals("0", resp.getCode());
        verify(client).breakOverride(7L, 3L, input);
    }

    @Test
    void 熔断恢复_非ADMIN403_不触core() {
        login(7L, "TESTER");
        stub.memberRoleResult(2);
        ServiceException ex = assertThrows(ServiceException.class,
                () -> controller.breakOverride(3L, new BreakOverrideInput("恢复", 24)));
        assertEquals(40301, ex.getCode(), "熔断临时恢复仅系统管理员（负责人不可自解）");
        verifyNoInteractions(client);
    }

    @Test
    void core视图JSON字段对齐_四维列表与嵌套项零静默丢() throws Exception {
        String coreJson = """
                {"month":"2026-09","projectId":null,
                 "total":{"value":"总计","amount":12.34,"calls":8},
                 "byProject":[{"value":"1","amount":10.00,"calls":5}],
                 "byModel":[{"value":"deepseek-chat","amount":2.34,"calls":3}],
                 "byCaller":[{"value":"7","amount":12.34,"calls":8}],
                 "byDay":[{"value":"2026-09-12","amount":12.34,"calls":8}]}""";
        CostOverviewView view = mapper.readValue(coreJson, CostOverviewView.class);
        assertEquals("2026-09", view.month());
        assertEquals(0, new BigDecimal("12.34").compareTo(view.total().amount()));
        assertEquals(8L, view.total().calls());
        assertEquals(1, view.byProject().size(), "项目维静默丢=看板缺维");
        assertEquals("deepseek-chat", view.byModel().get(0).value());
        assertEquals("2026-09-12", view.byDay().get(0).value());
    }
}
