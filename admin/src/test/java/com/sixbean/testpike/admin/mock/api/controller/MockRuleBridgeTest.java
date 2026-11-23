package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockPlaybackResultView;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioInput;
import com.sixbean.testpike.admin.mock.api.dto.MockScenarioView;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceInput;
import com.sixbean.testpike.admin.mock.api.dto.MockSpacePolicyRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceTransitionRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockStubDraftView;
import com.sixbean.testpike.admin.mock.api.dto.MockStubInput;
import com.sixbean.testpike.admin.mock.api.dto.MockStubView;
import com.sixbean.testpike.admin.mock.infrastructure.remote.client.CoreMockRuleClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Mock rule admin bridge contract test .
 * <p>Asserted surface: ①space create forwards projectId + the full nine-field
 * input untouched (save-chain gates — key shape, production-env ban — live in
 * core MockRuleValidator, no second check here per red line 6); ②space list
 * passes the fifteen-field projection through verbatim (null envId stays null,
 * version/priority never truncated — the UI renders honest empties from these);
 * ③stub create forwards spaceId + the full eleven-field rule row untouched;
 * ④stub list keeps core's evaluation order (priority asc) — the bridge must not
 * re-sort; ⑤core ServiceException with a human message propagates untouched.
 * <p>CoreMockRuleClient stand-in = L2 bridge-contract isolation (precedent:
 * ReleaseCandidateBridgeTest); the real admin→core /internal/mock chain is
 * verified at L3 in pack E.
 */
class MockRuleBridgeTest {

    private final CoreMockRuleClient client = mock(CoreMockRuleClient.class);
    private final MockSpaceController spaceController = new MockSpaceController(client);
    private final MockStubController stubController = new MockStubController(client);
    private final MockScenarioController scenarioController = new MockScenarioController(client);
    private final MockRecordingController recordingController = new MockRecordingController(client);

    @Test
    void playbackPreviewAndConfirmForwardPathIdsAndModeUntouched() {
        // Bridge contract : spaceId comes from the route, the
        // mode rides through verbatim, and core's result/draft lists pass
        // through field-for-field — no bridge-side filtering or re-validation.
        when(client.previewPlayback(7L, "fuzzy")).thenReturn(List.of(
                new MockStubDraftView("GET /orders/{id}", "回放 GET /orders/:id", "GET",
                        "template", "/orders/{id}", "[]", "{\"status\":200}",
                        "record", "recording:7")));
        var preview = recordingController.previewPlayback(3L, 7L, "fuzzy").getData();
        assertEquals(1, preview.size());
        assertEquals("recording:7", preview.get(0).sourceRef());
        verify(client).previewPlayback(7L, "fuzzy");

        when(client.playback(3L, 7L, "strict")).thenReturn(
                new MockPlaybackResultView(List.of(11L, 12L), List.of("GET /x"), 2));
        var filed = recordingController.playback(3L, 7L, "strict").getData();
        assertEquals(List.of(11L, 12L), filed.createdStubIds());
        assertEquals(2, filed.promotedCount());
        verify(client).playback(3L, 7L, "strict");
    }

    /**
 * Miss-policy bridge : spaceId rides the path, the policy
 * name and the optimistic-lock version ride the body — forwarded untouched.
 * Rung-name legality and the version conflict verdict are core's policy
 * door (single point, red line 6); the post-save view comes back
 * field-for-field so the UI re-renders the row from core's truth.
 */
    @Test
    void spacePolicy_forwardsSpaceIdPolicyAndVersionUntouched_viewPassesThrough() {
        LocalDateTime now = LocalDateTime.now();
        when(client.updateMissPolicy(101L, "passthrough", 4)).thenReturn(new MockSpaceView(
                101L, 7L, "订单仿真", "order-sbx", null, null,
                "strict", 1, "none", null, null, null, 10, 30, "passthrough",
                null, 5, now, now, null, null));

        MockSpaceView saved = spaceController.updatePolicy(
                101L, new MockSpacePolicyRequest("passthrough", 4)).getData();

        assertEquals("passthrough", saved.missPolicy(),
                "保存后的未命中策略原样透出（前端三档显示消费位）");
        assertEquals(5, saved.version(), "乐观锁版本由 core 自增后透出，桥面禁回填旧值");
        verify(client).updateMissPolicy(101L, "passthrough", 4);
    }

    /**
     * Missing intake numbers are refused at the bridge door with human wording
     * before hitting core (same posture as the transition door); an unknown
     * rung name is NOT judged here — core's validator owns the single spectrum.
     */
    @Test
    void spacePolicy_missingNumbers_refusedAtBridgeDoor() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceController.updatePolicy(101L, new MockSpacePolicyRequest(null, 4)));
        assertTrue(ex.getMessage().contains("策略"), "缺策略=人话 400");
        ex = assertThrows(ServiceException.class,
                () -> spaceController.updatePolicy(101L, new MockSpacePolicyRequest("reject", null)));
        assertTrue(ex.getMessage().contains("版本"), "缺乐观锁版本=人话 400");
    }

    @Test
    void spacePolicy_conflictCode_propagatesUntouched() {
        when(client.updateMissPolicy(101L, "reject", 3)).thenThrow(new ServiceException(
                "空间已被其他人修改（期望版本 3 已过期），请点「刷新」获取最新版本后重试", 40901));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceController.updatePolicy(101L, new MockSpacePolicyRequest("reject", 3)));
        assertEquals(Integer.valueOf(40901), ex.getCode(), "版本冲突码经桥不得降级成 500/400");
    }

    @Test
    void spaceCreate_forwardsProjectIdAndInputFieldsUntouched() {
        MockSpaceCreateRequest request = new MockSpaceCreateRequest(
                7L, "支付域仿真", "pay-sandbox", "alt-1", 42L,
                "prefix", "token", 50, 100000L, 10, 25, "reject");
        when(client.createSpace(org.mockito.ArgumentMatchers.eq(7L),
                org.mockito.ArgumentMatchers.any(MockSpaceInput.class))).thenReturn(101L);

        assertEquals(101L, spaceController.create(request).getData().longValue());

        ArgumentCaptor<MockSpaceInput> cap = ArgumentCaptor.forClass(MockSpaceInput.class);
        verify(client).createSpace(org.mockito.ArgumentMatchers.eq(7L), cap.capture());
        MockSpaceInput input = cap.getValue();
        assertEquals("支付域仿真", input.name());
        assertEquals("pay-sandbox", input.spaceKey());
        assertEquals("alt-1", input.alias());
        assertEquals(42L, input.envId());
        assertEquals("prefix", input.basePathMode());
        assertEquals("token", input.authMode());
        assertEquals(50, input.rateLimitRps());
        assertEquals(100000L, input.quotaDaily());
        assertEquals(10, input.priority());
        assertEquals(25, input.chaosPercent(), "chaos dial rides the bridge untouched");
        assertEquals("reject", input.missPolicy(), "miss policy rides the bridge untouched");
    }

    @Test
    void spaceList_passesFullProjectionThrough_noNullSwallowing() {
        LocalDateTime now = LocalDateTime.now();
        when(client.listSpaces(7L)).thenReturn(List.of(
                new MockSpaceView(1L, 7L, "订单仿真", "order-sbx", null, null,
                        "strict", 0, "none", null, null, null, 10, 0, "llm-fallback",
                        null, 0, now, now, 3, 0.5)));
        MockSpaceView head = spaceController.list(7L).getData().get(0);
        assertEquals(1L, head.id());
        assertEquals(7L, head.projectId());
        assertNull(head.alias(), "未填别名=null 诚实空位（桥面禁转空串谎报）");
        assertNull(head.envId());
        assertEquals(0, head.status(), "草稿态 0 原样透出");
        assertEquals("llm-fallback", head.missPolicy(),
                "未命中策略由 core 落默认档位后透出，桥面禁代填");
        assertNull(head.rateLimitRps(), "空=跟随 K8 默认，桥面禁代填数值");
        assertEquals(0, head.version());
        assertEquals(3, head.ruleCount().intValue(),
                "rule-count thumbnail rides the bridge verbatim (selector data source)");
        assertEquals(0.5, head.hitRate(), 1e-9,
                "hit-rate thumbnail is core-spectrum computed; bridge re-derives nothing");
    }

    @Test
    void stubCreate_forwardsSpaceIdAndThirteenRuleFieldsUntouched() {
        MockStubCreateRequest request = new MockStubCreateRequest(
                "下单成功", 5, "post", "template", "/orders/{id}",
                "[{\"type\":\"header\",\"name\":\"X-Tenant\",\"value\":\"t1\"}]",
                "{\"status\":200,\"body\":{\"code\":\"OK\"}}",
                "checkout-flow", "Started", "CREATED", "manual", "case:2001", true);
        when(client.createStub(org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.any(MockStubInput.class))).thenReturn(501L);

        assertEquals(501L, stubController.create(101L, request).getData().longValue());

        ArgumentCaptor<MockStubInput> cap = ArgumentCaptor.forClass(MockStubInput.class);
        verify(client).createStub(org.mockito.ArgumentMatchers.eq(101L), cap.capture());
        MockStubInput input = cap.getValue();
        assertEquals("下单成功", input.name());
        assertEquals(5, input.priority());
        assertEquals("post", input.method());
        assertEquals("template", input.pathMatchType());
        assertEquals("/orders/{id}", input.pathPattern());
        assertTrue(input.matchersJson().contains("X-Tenant"), "匹配器 JSON 原文透出不重排");
        assertTrue(input.responseJson().contains("\"code\":\"OK\""), "响应载荷原文透出");
        assertEquals("checkout-flow", input.scenarioName());
        assertEquals("Started", input.requiredState(), "触发状态列桥面原样转发（判定归 core 单点）");
        assertEquals("CREATED", input.newState(), "推进状态列桥面原样转发");
        assertEquals("manual", input.source());
        assertEquals("case:2001", input.sourceRef());
        assertEquals(Boolean.TRUE, input.enabled());
    }

    @Test
    void stubList_keepsCoreEvaluationOrder_noBridgeResort() {
        LocalDateTime now = LocalDateTime.now();
        when(client.listStubs(101L)).thenReturn(List.of(
                new MockStubView(1L, 101L, "先匹配", 1, "ANY", "prefix", "/a",
                        null, null, null, null, null, "manual", null, true, 1, now, now),
                new MockStubView(2L, 101L, "后匹配", 9, "GET", "exact", "/b",
                        null, null, null, null, null, "manual", null, false, 2, now, now)));
        List<MockStubView> rows = stubController.list(101L).getData();
        assertEquals(1, rows.get(0).priority(), "core 顺序=优先级升序首匹配，桥面禁重排");
        assertEquals("先匹配", rows.get(0).name());
        assertEquals(9, rows.get(1).priority());
        assertEquals(Boolean.FALSE, rows.get(1).enabled(), "停用开关原样透出");
    }

    /**
     * Publish bridge forwards the three intake numbers untouched and returns the
     * core's post-transition fifteen-field view verbatim (edge set, optimistic
     * lock and the publish-time production-env re-check are core's single point —
     * the bridge re-decides nothing, red line 6).
     */
    @Test
    void spaceTransition_forwardsEdgeAndOptimisticLockUntouched_viewPassesThrough() {
        LocalDateTime now = LocalDateTime.now();
        when(client.transitionSpace(101L, 1, 0)).thenReturn(new MockSpaceView(
                101L, 7L, "订单仿真", "order-sbx", null, null,
                "strict", 1, "none", null, null, null, 10, 30, "llm-fallback",
                null, 1, now, now, null, null));

        MockSpaceView moved = spaceController.transition(
                101L, new MockSpaceTransitionRequest(1, 0)).getData();

        assertEquals(1, moved.status(), "发布后 status=1 原样透出（前端三态徽标消费位）");
        assertEquals(1, moved.version(), "乐观锁版本由 core 自增后透出，桥面禁回填旧值");
        org.mockito.Mockito.verify(client).transitionSpace(101L, 1, 0);
    }

    /**
     * A lost optimistic-lock race (core 40901) must reach the caller with its
     * business code intact — the UI distinguishes "refresh and retry" from a
     * plain validation refusal by exactly this code.
     */
    @Test
    void spaceTransition_conflictCode_propagatesUntouched() {
        when(client.transitionSpace(101L, 2, 3)).thenThrow(new ServiceException(
                "空间已被其他人修改（期望版本 3 已过期），请点「刷新」获取最新版本后重试", 40901));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceController.transition(101L, new MockSpaceTransitionRequest(2, 3)));
        assertEquals(Integer.valueOf(40901), ex.getCode(), "版本冲突码经桥不得降级成 500/400");
    }

    /**
     * Missing intake numbers are refused at the bridge door with a human-worded
     * 400 before hitting core (thin-controller parameter intake, same posture as
     * the space-create projectId check).
     */
    @Test
    void spaceTransition_missingNumbers_refusedAtBridgeDoor() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceController.transition(101L, new MockSpaceTransitionRequest(null, 0)));
        assertTrue(ex.getMessage().contains("目标状态"), "缺目标状态=人话 400");
        ex = assertThrows(ServiceException.class,
                () -> spaceController.transition(101L, new MockSpaceTransitionRequest(1, null)));
        assertTrue(ex.getMessage().contains("版本"), "缺乐观锁版本=人话 400");
    }

    @Test
    void coreHumanError_propagatesUntouched_noRewrite() {
        when(client.listSpaces(7L)).thenThrow(new ServiceException(
                "环境档案为生产类型，禁止绑定 Mock 空间", 400));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> spaceController.list(7L));
        assertTrue(ex.getMessage().contains("生产"), "人话错误不得改写不得吞码");
    }

    /**
 * Scenario bridge create : the owning space id comes from the path,
 * the machine declaration from the body — both forwarded untouched. The
 * definition JSON must arrive byte-for-byte because the declaration kernel
 * in core is the only place that parses it (red line 6: no bridge-side
 * re-shaping, no second gate).
 */
    @Test
    void scenarioCreate_forwardsSpaceIdAndDefinitionJsonVerbatim() {
        String definition = "{\"states\":[\"STARTED\",\"PAID\"],\"initial\":\"STARTED\","
                + "\"scope\":\"session\",\"transitions\":[]}";
        when(client.createScenario(org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.any(MockScenarioInput.class))).thenReturn(501L);

        assertEquals(501L, scenarioController.create(101L,
                new MockScenarioCreateRequest("checkout-flow", definition)).getData().longValue());

        ArgumentCaptor<MockScenarioInput> cap = ArgumentCaptor.forClass(MockScenarioInput.class);
        verify(client).createScenario(org.mockito.ArgumentMatchers.eq(101L), cap.capture());
        assertEquals("checkout-flow", cap.getValue().name());
        assertEquals(definition, cap.getValue().definitionJson(),
                "机器声明原文透出——解析语义单源在共享声明核，桥面禁重排禁重写");
    }

    /**
     * Scenario list keeps core's name order (the UI's picklist and the stub
     * editor's state dropdowns read positions off this list) and passes the
     * six-field projection through — a null definition never becomes "{}".
     */
    @Test
    void scenarioList_keepsCoreNameOrder_projectionPassesThrough() {
        LocalDateTime now = LocalDateTime.now();
        when(client.listScenarios(101L)).thenReturn(List.of(
                new MockScenarioView(1L, 101L, "checkout-flow",
                        "{\"states\":[\"STARTED\"]}", now, now),
                new MockScenarioView(2L, 101L, "refund-flow",
                        "{\"states\":[\"OPEN\"]}", now, now)));

        List<MockScenarioView> rows = scenarioController.list(101L).getData();

        assertEquals(2, rows.size());
        assertEquals("checkout-flow", rows.get(0).name(), "core 名称序不得被桥面重排");
        assertEquals(101L, rows.get(1).spaceId());
        assertTrue(rows.get(1).definitionJson().contains("OPEN"),
                "声明 JSON 原样透出供编辑面回填");
    }

    /**
     * A duplicate scenario name (core's uniqueness refusal) crosses the bridge
     * with its human wording intact — the editor shows exactly this sentence.
     */
    @Test
    void scenarioCreate_duplicateNameError_propagatesUntouched() {
        when(client.createScenario(org.mockito.ArgumentMatchers.eq(101L),
                org.mockito.ArgumentMatchers.any(MockScenarioInput.class)))
                .thenThrow(new ServiceException(
                        "该空间下场景「checkout-flow」已存在——同一空间内场景名唯一", 400));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> scenarioController.create(101L,
                        new MockScenarioCreateRequest("checkout-flow", "{}")));
        assertTrue(ex.getMessage().contains("场景名唯一"), "人话拒语不得被桥面改写成通用 500");
    }
}
