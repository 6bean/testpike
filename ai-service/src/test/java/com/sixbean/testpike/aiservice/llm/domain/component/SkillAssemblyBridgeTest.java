package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillUsageRemoteInput;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class SkillAssemblyBridgeTest {

    private static final long PROJECT_ID = 3L;

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final SkillAssemblyBridge bridge = new SkillAssemblyBridge(gateway,
            new LlmPromptAssembler(new TokenCounter()), new SkillUsageRecorder(gateway, mock(CtxMemoryHitWriter.class)), new AiCtxConfigResolver(gateway, new AiCtxProperties()));

    private static SkillAssemblyRemoteItem item(long id, int role, int priority, String body) {
        return new SkillAssemblyRemoteItem(id, "SKILL-" + id, role, priority, 1, 2, body, 0);
    }

    private static LlmEndpointConfig endpoint(Integer modelLimit) {
        return new LlmEndpointConfig("openai", "m", "k", "http://x", "config", modelLimit);
    }

    
    @Test
    void 候选装配注入段与留痕转发() {
        when(gateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(11L, 3, 5, "接口断言必须显式"),
                item(12L, 1, 9, "脚本开发规范")));
        String segment = bridge.assemblySegment(SkillStage.CASE_GEN, PROJECT_ID, 42L, endpoint(null));
        assertTrue(segment.contains("项目 SKILL 规范"), "通用 SKILL 段缺失: " + segment);
        assertTrue(segment.contains("接口断言必须显式"));
        assertFalse(segment.contains("脚本开发规范"), "role=1 不进 stage1（路由单一判型点）");

        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordSkillUsages(cap.capture());
        List<SkillUsageRemoteInput> sent = cap.getValue();
        assertEquals(1, sent.size(), "仅被消费阶段命中的候选留痕");
        assertEquals(11L, sent.get(0).skillId());
        assertEquals(2, sent.get(0).stage(), "CASE_GEN→usage.stage=2");
        assertEquals(0, sent.get(0).dropped());
        assertEquals(42L, sent.get(0).taskId());
    }

 
    @Test
    void 超预算丢弃不抛留痕dropped() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getSysConfig("ai.doc.token-budget")).thenReturn("100");
        when(gateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(21L, 3, 9, "范".repeat(400))));
        String segment = bridge.assemblySegment(SkillStage.SCRIPT_GEN, PROJECT_ID, 7L, endpoint(1_000_000));
        assertTrue(segment.isEmpty(), "超预算=全丢弃段空，禁半残注入");

        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordSkillUsages(cap.capture());
        assertEquals(1, cap.getValue().get(0).dropped(), "TC-S-05 丢弃留痕 dropped=1");
        assertEquals(3, cap.getValue().get(0).stage(), "SCRIPT_GEN→stage=3");
    }

    
    @Test
    void 模型上限取小生效() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                item(31L, 3, 5, "规".repeat(400))));
        String segment = bridge.assemblySegment(SkillStage.F2_EXTRACT, PROJECT_ID, null, endpoint(50));
        assertTrue(segment.isEmpty(), "min(5000,50)=50 超限应丢弃");
        verify(gateway, timeout(3000)).recordSkillUsages(anyList());
    }

    
    @Test
    void 空候选零留痕() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getProjectAssembly(anyLong())).thenReturn(List.of());
        assertEquals("", bridge.assemblySegment(SkillStage.CASE_GEN, PROJECT_ID, 1L, endpoint(null)));
        verify(gateway, never()).recordSkillUsages(anyList());
    }

    
    @Test
    void 取数异常failsoft() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getProjectAssembly(anyLong())).thenThrow(new RuntimeException("core down"));
        assertEquals("", bridge.assemblySegment(SkillStage.CASE_GEN, PROJECT_ID, 1L, endpoint(null)));
        verify(gateway, never()).recordSkillUsages(anyList());
    }
}
