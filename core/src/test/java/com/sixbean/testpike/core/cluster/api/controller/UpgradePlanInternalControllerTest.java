package com.sixbean.testpike.core.cluster.api.controller;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanCreateInput;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanImpactView;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanInfo;
import com.sixbean.testpike.core.cluster.api.dto.UpgradePlanStepView;
import com.sixbean.testpike.core.cluster.domain.service.UpgradePlanService;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class UpgradePlanInternalControllerTest {

    private final UpgradePlanService service = mock(UpgradePlanService.class);
    private final UpgradePlanInternalController controller = new UpgradePlanInternalController(service);

    @Test
    void 创建入参照样转发_计划行九字段原样透出() {
        LocalDateTime now = LocalDateTime.now();
        UpgradePlanCreateInput input = new UpgradePlanCreateInput("engine", List.of("MOBILEWRIGHT"),
                List.of("node-a"), 2, "suite-7", "0.0.63",null,null, "bowen");
        UpgradePlanInfo saved = new UpgradePlanInfo(9L, "engine", "[\"MOBILEWRIGHT\"]",
                "{\"canaryNodeIds\":[\"node-a\"],\"batchSize\":2,\"cool\":300,\"baselineRef\":\"suite-7\"}",
                "0.0.63",null,null,null,null, "PREVIEW", null, "bowen", now);
        when(service.create(input)).thenReturn(saved);

        UpgradePlanInfo got = controller.create(input).getData();
        assertEquals(9L, got.id());
        assertEquals("engine", got.scope());
        assertEquals("[\"MOBILEWRIGHT\"]", got.targetEngineCodes());
        assertEquals("0.0.63", got.manifestVersion());
        assertEquals("PREVIEW", got.status());
        assertNull(got.brakeReason());
        assertEquals("bowen", got.operator());
        assertEquals(now, got.createdAt());
        verify(service).create(input);
    }

    @Test
    void 列表与详情_投影不加工() {
        UpgradePlanInfo a = new UpgradePlanInfo(1L, "node", "[]", "{}", null,null,null,null,null,
                "RUNNING", null, "ops", LocalDateTime.now());
        when(service.listAll()).thenReturn(List.of(a));
        when(service.get(1L)).thenReturn(a);
        assertEquals(1, controller.list().getData().size());
        assertEquals("RUNNING", controller.get(1L).getData().status());
        verify(service).listAll();
        verify(service).get(1L);
    }

    @Test
    void 步列表_全字段透出含命令id与证据链() {
        UpgradePlanStepView step = new UpgradePlanStepView(11L, 9L, "node-a", "engine",
                "MOBILEWRIGHT", "canary", "1.2.0", "1.3.0", "SUCCESS", 77L, "{\"k\":\"v\"}",
                null, null);
        when(service.steps(9L)).thenReturn(List.of(step));
        UpgradePlanStepView got = controller.steps(9L).getData().get(0);
        assertEquals("node-a", got.nodeId());
        assertEquals("canary", got.phase());
        assertEquals("1.2.0", got.currentVersion());
        assertEquals("1.3.0", got.targetVersion());
        assertEquals(77L, got.commandId());
        assertEquals("{\"k\":\"v\"}", got.evidence());
    }

    @Test
    void 四动作纯转发_CAS归因由服务上抛() {
        UpgradePlanInfo running = new UpgradePlanInfo(9L, "engine", "[]", "{}", null,null,null,null,null,
                "RUNNING", null, "ops", LocalDateTime.now());
        when(service.start(9L)).thenReturn(running);
        when(service.pause(9L)).thenReturn(running);
        when(service.resume(9L)).thenReturn(running);
        when(service.abort(9L)).thenReturn(running);
        assertEquals("RUNNING", controller.start(9L).getData().status());
        controller.pause(9L);
        controller.resume(9L);
        controller.abort(9L);
        verify(service).start(9L);
        verify(service).pause(9L);
        verify(service).resume(9L);
        verify(service).abort(9L);

        when(service.pause(3L)).thenThrow(new ServiceException(
                "升级计划 #3 当前状态「预览」不能暂停（进行中态才能暂停，禁跳步）"));
        ServiceException err = assertThrows(ServiceException.class, () -> controller.pause(3L));
        assertEquals(true, err.getMessage().contains("禁跳步"));
    }

    @Test
    void 影响面现算口_十件字段原样透出() {
        UpgradePlanCreateInput input = new UpgradePlanCreateInput("node", null, null, null,
                null, null,null,null, "bowen");
        UpgradePlanImpactView impact = new UpgradePlanImpactView("node", List.of(), List.of("node-a"),
                8, 8, 5, 2, 300, 92, 3, List.of());
        when(service.impact(input)).thenReturn(impact);
        UpgradePlanImpactView got = controller.impact(input).getData();
        assertEquals("node", got.scope());
        assertEquals(List.of("node-a"), got.canaryNodeIds());
        assertEquals(8, got.affectedNodes());
        assertEquals(8, got.stepCount());
        assertEquals(5, got.waves());
        assertEquals(2, got.batchSize());
        assertEquals(300, got.cooldownSeconds());
        assertEquals(92, got.estimatedMinutes());
        assertEquals(3, got.activePlans());
        verify(service).impact(input);
    }

    @Test
    void 服务异常_原样上抛不吞() {
        when(service.listAll()).thenThrow(new IllegalStateException("库连不上"));
        assertThrows(IllegalStateException.class, () -> controller.list());
    }
}
