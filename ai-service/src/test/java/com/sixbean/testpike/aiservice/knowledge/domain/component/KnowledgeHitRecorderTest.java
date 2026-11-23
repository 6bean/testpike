package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.KnowledgeHitRemoteInput;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


class KnowledgeHitRecorderTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final KnowledgeHitRecorder recorder = new KnowledgeHitRecorder(gateway);

    private KnowledgeSearchItem hit(String chunkId, long docId) {
        return new KnowledgeSearchItem(chunkId, docId, 0L, "TEST_CASE", "t", "c",
                0.9, 0.8);
    }

    
    @Test
    void 命中按doc聚合转发_场景与任务id透传() {
        recorder.record(77L, "GEN_CASE", List.of(
                hit("chunk-a1", 11L), hit("chunk-a2", 11L), hit("chunk-b1", 12L)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeHitRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordKnowledgeHits(cap.capture());
        List<KnowledgeHitRemoteInput> sent = cap.getValue();
        assertEquals(2, sent.size(), "两 doc=两行留痕");
        KnowledgeHitRemoteInput first = sent.stream()
                .filter(i -> i.docId() == 11L).findFirst().orElseThrow();
        assertEquals(77L, first.taskId());
        assertEquals("GEN_CASE", first.scene());
        assertEquals(List.of("chunk-a1", "chunk-a2"), first.chunkIds(), "命中序保序聚合");
        assertEquals(List.of("chunk-b1"), sent.stream()
                .filter(i -> i.docId() == 12L).findFirst().orElseThrow().chunkIds());
    }

    
    @Test
    void 空命中零HTTP() {
        recorder.record(1L, "GEN_CASE", List.of());
        recorder.record(1L, "GEN_CASE", null);
        verifyNoInteractions(gateway);
    }

 
    @Test
    void 写失败吞掉不阻断() {
        doThrow(new RuntimeException("core down")).when(gateway).recordKnowledgeHits(anyList());
        recorder.record(1L, "SCRIPT_GEN", List.of(hit("c1", 5L)));
        verify(gateway, timeout(3000)).recordKnowledgeHits(anyList());
        assertTrue(true);
    }

    
    @Test
    void taskId可空透传() {
        recorder.record(null, "DDL_DICT", List.of(hit("c1", 9L)));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeHitRemoteInput>> cap = ArgumentCaptor.forClass(List.class);
        verify(gateway, timeout(3000)).recordKnowledgeHits(cap.capture());
        assertEquals(9L, cap.getValue().get(0).docId());
        org.junit.jupiter.api.Assertions.assertNull(cap.getValue().get(0).taskId());
    }
}
