package com.sixbean.testpike.admin.ai.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Wire contract for the queue-waiting notice on the batch progress door.
 *
 * Core AiBatchInfo carries the three-part sentence on queueNotice; the admin
 * record must bind the same JSON name or the notice silently drops on the
 * drawer (lenient unknown-property mapping) — the exact failure mode the
 * cancelled bucket fixed once before (AiTaskCancelEndpointTest precedent).
 */
class AiBatchQueueNoticeWireTest {

    @Test
    void adminRecordDeclaresTheQueueNoticeComponent() {
        assertTrue(Arrays.stream(AiBatchView.class.getRecordComponents())
                        .map(java.lang.reflect.RecordComponent::getName)
                        .anyMatch("queueNotice"::equals),
                "AiBatchView must carry queueNotice so the server sentence reaches the browser");
    }

    @Test
    void coreWireRoundTripKeepsTheSentence() throws Exception {
        String coreWire = "{\"id\":3,\"projectId\":42,\"operatorId\":5,\"total\":10,"
                + "\"pending\":4,\"running\":0,\"done\":6,\"failed\":0,\"cancelled\":0,"
                + "\"createdAt\":\"2026-11-21T01:00:00\",\"items\":[],"
                + "\"queueNotice\":\"生成队列当前积压 41 条待处理任务，本批次 4 条任务正在排队；"
                + "按最近 6 小时平均耗时 95 秒/条、4 路并发估算，预计约 17 分钟后完成当前排队。\"}";

        AiBatchView view = new ObjectMapper().readValue(coreWire, AiBatchView.class);

        assertEquals(4, view.pending());
        assertTrue(view.queueNotice().startsWith("生成队列当前积压 41 条"),
                "the bound sentence must be the server-assembled text, verbatim");
    }
}
