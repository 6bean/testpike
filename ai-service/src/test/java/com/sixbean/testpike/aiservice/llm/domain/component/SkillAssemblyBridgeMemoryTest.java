package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxConfigResolver;
import com.sixbean.testpike.aiservice.aichat.domain.component.AiCtxProperties;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillAssemblyRemoteItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Bridge-side memory injection wiring : the cap
 * {@code ai.ctx.memory-max-items} (K8 hot read via AiCtxConfigResolver) trims
 * memory items newest-first BEFORE assembly — the whole table never rides into
 * the system prompt, and no second injection chain exists (SKILL channel reused).
 * Injected ids reach the hit writer asynchronously through the recorder's
 * bounded queue (observation plane never blocks generation). Cold start with no
 * rows returns the empty segment unchanged (honest absence).
 */
class SkillAssemblyBridgeMemoryTest {

    private static final long PROJECT_ID = 3L;

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final CtxMemoryHitWriter hitWriter = mock(CtxMemoryHitWriter.class);
    private final SkillUsageRecorder recorder = new SkillUsageRecorder(gateway, hitWriter);
    private final SkillAssemblyBridge bridge = new SkillAssemblyBridge(gateway,
            new LlmPromptAssembler(new TokenCounter()), recorder,
            new AiCtxConfigResolver(gateway, new AiCtxProperties()));

    private static SkillAssemblyRemoteItem memory(long id, String body) {
        return new SkillAssemblyRemoteItem(id, "项目记忆", 3, 0, 1, 0, body, 1);
    }

    private static SkillAssemblyRemoteItem skill(long id, String body) {
        return new SkillAssemblyRemoteItem(id, "SKILL-" + id, 3, 5, 1, 2, body, 0);
    }

    @Test
    void 记忆按上限取舍最新在前_注入命中异步自增() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getSysConfig("ai.doc.token-budget")).thenReturn("4000");
        when(gateway.getSysConfig(AiCtxConfigResolver.KEY_MEMORY_MAX_ITEMS)).thenReturn("2");
        when(gateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of(
                skill(9L, "接口断言必须显式"),
                memory(21, "记忆一"), memory(22, "记忆二"),
                memory(23, "记忆三"), memory(24, "记忆四")));

        String segment = bridge.assemblySegment(SkillStage.CASE_GEN, PROJECT_ID, 42L, null);

        assertTrue(segment.contains("接口断言必须显式"), "SKILL 通道零破坏");
        assertTrue(segment.contains("记忆四"), "最新记忆缺失: " + segment);
        assertTrue(segment.contains("记忆三"));
        assertFalse(segment.contains("记忆二"), "超出上限 2 的旧记忆必须被裁剪");
        assertFalse(segment.contains("记忆一"));

        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(hitWriter, timeout(2000)).recordHits(captor.capture());
        assertEquals(List.of(24L, 23L), captor.getValue(), "命中=实际注入的两条最新");
    }

    @Test
    void 冷启动空集诚实缺席_零段零命中() {
        when(gateway.getSysConfig(anyString())).thenReturn(null);
        when(gateway.getProjectAssembly(PROJECT_ID)).thenReturn(List.of());

        assertEquals("", bridge.assemblySegment(SkillStage.CASE_GEN, PROJECT_ID, 9L, null));
        verifyNoInteractions(hitWriter);
    }
}
