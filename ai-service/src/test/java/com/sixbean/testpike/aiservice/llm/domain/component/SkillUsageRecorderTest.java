package com.sixbean.testpike.aiservice.llm.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.SkillUsageRemoteInput;
import com.sixbean.testpike.aiservice.aictx.domain.component.CtxMemoryHitWriter;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


class SkillUsageRecorderTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final SkillUsageRecorder recorder = new SkillUsageRecorder(gateway, mock(CtxMemoryHitWriter.class));

    
    @Test
    void 留痕批量转发补齐taskId() {
        recorder.record(77L, List.of(
                new SkillUsageTrace(11L, 3, 2, 0),
                new SkillUsageTrace(12L, 1, 2, 1)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordSkillUsages(cap.capture());
        List<SkillUsageRemoteInput> sent = cap.getValue();
        assertEquals(2, sent.size());
        assertEquals(77L, sent.get(0).taskId());
        assertEquals(11L, sent.get(0).skillId());
        assertEquals(3, sent.get(0).version());
        assertEquals(2, sent.get(0).stage());
        assertEquals(0, sent.get(0).dropped());
        assertEquals(12L, sent.get(1).skillId());
        assertEquals(1, sent.get(1).dropped(), "TC-S-05 丢弃留痕 dropped=1 透传");
    }

    
    @Test
    void taskId可空透传() {
        recorder.record(null, List.of(new SkillUsageTrace(5L, 2, 1, 0)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<SkillUsageRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordSkillUsages(cap.capture());
        assertNull(cap.getValue().get(0).taskId());
    }

    
    @Test
    void 空留痕零调用() {
        recorder.record(1L, List.of());
        recorder.record(1L, null);
        verifyNoInteractions(gateway);
    }

 
    @Test
    void 写失败吞异常主链无感() {
        doThrow(new RuntimeException("core down"))
                .when(gateway).recordSkillUsages(any());
        recorder.record(2L, List.of(new SkillUsageTrace(9L, 1, 3, 0)));
        recorder.record(3L, List.of(new SkillUsageTrace(10L, 1, 3, 1)));
        verify(gateway, timeout(3000).atLeast(2)).recordSkillUsages(any());
    }

    
    @Test
    void 队列满丢弃计数不抛() throws Exception {
        java.util.concurrent.CountDownLatch enter = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch block = new java.util.concurrent.CountDownLatch(1);
        doThrow(new RuntimeException("core down"))
                .when(gateway).recordSkillUsages(any());
        org.mockito.Mockito.doAnswer(inv -> {
            enter.countDown();
            block.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return null;
        }).doNothing().when(gateway).recordSkillUsages(any());
        recorder.record(0L, List.of(trace()));
        assertTrue(enter.await(3, java.util.concurrent.TimeUnit.SECONDS), "worker 未进入阻塞");
        for (int i = 0; i < SkillUsageRecorder.QUEUE_CAPACITY + 1; i++) {
            recorder.record(1L, List.of(trace()));
        }
        block.countDown();
        assertTrue(recorder.droppedCount() >= 1,
                "队列满应计数丢弃，实得 " + recorder.droppedCount());
    }

    private static SkillUsageTrace trace() {
        return new SkillUsageTrace(1L, 1, 2, 0);
    }
}
