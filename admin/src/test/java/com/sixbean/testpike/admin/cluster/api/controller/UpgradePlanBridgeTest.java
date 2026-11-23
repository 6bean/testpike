package com.sixbean.testpike.admin.cluster.api.controller;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanCreateRequest;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.admin.cluster.api.dto.UpgradePlanView;
import com.sixbean.testpike.admin.cluster.infrastructure.remote.client.CoreUpgradePlanClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class UpgradePlanBridgeTest {

    private final CoreUpgradePlanClient client = mock(CoreUpgradePlanClient.class);
    private final UpgradePlanController controller = new UpgradePlanController(client);

    @BeforeEach
    void login() {
        UserContext.set(new CurrentUser(7L, "bowen", "名", "部", List.of("TEST_ADMIN"), 0L, null));
    }

    @AfterEach
    void logout() {
        UserContext.clear();
    }

    @Test
    void 创建桥_入参照样转发_计划行九字段完整投影() {
        LocalDateTime now = LocalDateTime.now();
        UpgradePlanCreateRequest request = new UpgradePlanCreateRequest("engine",
                List.of("MOBILEWRIGHT"), List.of("node-a"), 2, "suite-7", "0.0.63",null,null, null);
        UpgradePlanView saved = new UpgradePlanView(9L, "engine", "[\"MOBILEWRIGHT\"]",
                "{\"canaryNodeIds\":[\"node-a\"],\"batchSize\":2,\"cool\":300,\"baselineRef\":\"suite-7\"}",
                "0.0.63",null,null,null,null, "PREVIEW", null, "bowen", now);
        when(client.create(any())).thenReturn(saved);

        UpgradePlanView got = controller.create(request).getData();
        assertEquals(9L, got.id());
        assertEquals("engine", got.scope());
        assertEquals("[\"MOBILEWRIGHT\"]", got.targetEngineCodes());
        assertEquals("0.0.63", got.manifestVersion());
        assertEquals("PREVIEW", got.status());
        assertNull(got.brakeReason());
        assertEquals(now, got.createdAt());
        ArgumentCaptor<UpgradePlanCreateRequest> forwarded =
                ArgumentCaptor.forClass(UpgradePlanCreateRequest.class);
        verify(client).create(forwarded.capture());
        UpgradePlanCreateRequest sent = forwarded.getValue();
        assertEquals("engine", sent.scope());
        assertEquals(List.of("MOBILEWRIGHT"), sent.engineCodes());
        assertEquals(List.of("node-a"), sent.canaryNodeIds());
        assertEquals(2, sent.batchSize());
        assertEquals("suite-7", sent.baselineRef());
        assertEquals("0.0.63", sent.manifestVersion());
        assertEquals("bowen", sent.operator());
    }

    @Test
    void 创建与影响面桥_请求体谎报操作人无效_以登录态身份覆盖() {
        UpgradePlanCreateRequest lying = new UpgradePlanCreateRequest("node", null, null,
                null, null, null,null,null, "hacker");
        when(client.create(any())).thenReturn(null);
        when(client.impact(any())).thenReturn(null);
        controller.create(lying);
        controller.impact(lying);
        ArgumentCaptor<UpgradePlanCreateRequest> cap =
                ArgumentCaptor.forClass(UpgradePlanCreateRequest.class);
        verify(client).create(cap.capture());
        verify(client).impact(cap.capture());
        List<UpgradePlanCreateRequest> sent = cap.getAllValues();
        assertEquals("bowen", sent.get(0).operator(), "谎报值不得透传给 core");
        assertEquals("bowen", sent.get(1).operator());
        assertEquals("hacker", lying.operator(), "入参对象不回改（转发副本覆盖）");
    }

    @Test
    void 列表与详情与步_投影不加工含命令id证据链() {
        UpgradePlanView a = new UpgradePlanView(1L, "node", "[]", "{}", null,null,null,null,null,
                "RUNNING", null, "ops", LocalDateTime.now());
        when(client.listAll()).thenReturn(List.of(a));
        when(client.get(anyLong())).thenReturn(a);
        UpgradePlanStepView step = new UpgradePlanStepView(11L, 1L, "node-a", "engine",
                "MOBILEWRIGHT", "canary", "1.2.0", "1.3.0", "SUCCESS", 77L, "{\"k\":\"v\"}",
                null, null);
        when(client.steps(anyLong())).thenReturn(List.of(step));

        assertEquals(1, controller.list().getData().size());
        assertEquals("RUNNING", controller.get(1L).getData().status());
        UpgradePlanStepView got = controller.steps(1L).getData().get(0);
        assertEquals(77L, got.commandId());
        assertEquals("{\"k\":\"v\"}", got.evidence());
        assertEquals("1.2.0", got.currentVersion());
    }

    @Test
    void 四动作纯转发_返回最新态() {
        UpgradePlanView running = new UpgradePlanView(9L, "engine", "[]", "{}", null,null,null,null,null,
                "RUNNING", null, "ops", LocalDateTime.now());
        when(client.start(anyLong())).thenReturn(running);
        when(client.pause(anyLong())).thenReturn(running);
        when(client.resume(anyLong())).thenReturn(running);
        when(client.abort(anyLong())).thenReturn(running);
        assertEquals("RUNNING", controller.start(9L).getData().status());
        controller.pause(9L);
        controller.resume(9L);
        controller.abort(9L);
        verify(client).start(9L);
        verify(client).pause(9L);
        verify(client).resume(9L);
        verify(client).abort(9L);
    }

    @Test
    void 影响面桥_十件字段原样透出() {
        UpgradePlanCreateRequest request = new UpgradePlanCreateRequest("node", null, null,
                null, null, null,null,null, null);
        UpgradePlanImpactView impact = new UpgradePlanImpactView("node", List.of(),
                List.of("node-a"), 8, 8, 5, 2, 300, 92, 3, List.of());
        when(client.impact(any())).thenReturn(impact);
        UpgradePlanImpactView got = controller.impact(request).getData();
        assertEquals(8, got.affectedNodes());
        assertEquals(5, got.waves());
        assertEquals(92, got.estimatedMinutes());
        assertEquals(3, got.activePlans());
    }

    @Test
    void core人话异常_原样上抛不吞() {
        when(client.pause(anyLong())).thenThrow(new ServiceException(
                "升级计划 #3 当前状态「预览」不能暂停（进行中态才能暂停，禁跳步）"));
        ServiceException err = assertThrows(ServiceException.class, () -> controller.pause(3L));
        assertEquals(true, err.getMessage().contains("禁跳步"));
        when(client.create(any())).thenThrow(new ServiceException("当前无可升级部件——台账里没有落在「可升级（黄）」态的目标节点×部件"));
        assertThrows(ServiceException.class, () -> controller.create(
                new UpgradePlanCreateRequest("node", null, null, null, null, null,null,null, null)));
    }
}
