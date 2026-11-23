package com.sixbean.testpike.aiservice.explore.domain.service.impl;

import com.sixbean.testpike.aiservice.explore.domain.component.ExploreLoopKernel;
import com.sixbean.testpike.aiservice.explore.infrastructure.remote.ExploreSessionBrief;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.taskstream.domain.component.TaskProcessEventStore;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.mq.ExploreRunMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * supplementary clause (failure must feed back) — when an exploration
 * run dies, the process event ledger must end with the shaped failure line
 * (category + cause + suggestion + retry entry from the single-source
 * formatter), never silence: today a provider outage only reaches the MQ
 * retry/dead-letter bookkeeping and the session's tail just stops mid-stream.
 * The exception still propagates unchanged (the event never swallows the
 * failure — container retry semantics stay intact), and a legacy wiring
 * without the store stays a zero-touch no-op.
 */
class ExploreFailFeedbackProcessEventTest {

    private static final long SESSION_ID = 880L;

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final ExploreLoopKernel loopKernel = mock(ExploreLoopKernel.class);
    private final TaskProcessEventStore store = mock(TaskProcessEventStore.class);

    private ExploreRunServiceImpl service(boolean withStore) {
        ExploreRunServiceImpl s = new ExploreRunServiceImpl(coreGateway, loopKernel);
        s.setProcessEvents(withStore ? store : null);
        return s;
    }

    private ExploreSessionBrief runningSession() {
        return new ExploreSessionBrief(SESSION_ID, 35L, "验证下单流程",
                "https://shop.example.com", 1, 0, 0, null, null, 7L, null);
    }

    @Test
    void 循环抛出异常_末事件带失败归类且异常照抛() {
        when(coreGateway.getExploreSession(SESSION_ID)).thenReturn(runningSession());
        doThrow(new ServiceException("模型供应商不可达，全部候选端点失败",
                BizErrorCode.SERVICE_UNAVAILABLE))
                .when(loopKernel).run(any(), eq(7L));

        ExploreRunServiceImpl service = service(true);
        assertThrows(ServiceException.class,
                () -> service.run(new ExploreRunMessage(SESSION_ID, 35L, 7L)));

        ArgumentCaptor<String> level = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(store).record(eq("explore"), eq(String.valueOf(SESSION_ID)),
                eq(String.valueOf(SESSION_ID)), level.capture(), summary.capture(), any());
        assertTrue("error".equals(level.getValue()), "终态失败必落 error 级: " + level.getValue());
        assertTrue(summary.getValue().contains("失败"),
                "末事件必须是失败句式: " + summary.getValue());
        assertTrue(summary.getValue().contains("建议"),
                "末事件必须带可操作建议: " + summary.getValue());
    }

    @Test
    void 会话已终态_零事件零循环() {
        ExploreSessionBrief terminal = new ExploreSessionBrief(SESSION_ID, 35L, "验证下单流程",
                "https://shop.example.com", 2, 5, 3, null, null, 7L, null);
        when(coreGateway.getExploreSession(SESSION_ID)).thenReturn(terminal);

        service(true).run(new ExploreRunMessage(SESSION_ID, 35L, 7L));

        verifyNoInteractions(store);
        verify(loopKernel, org.mockito.Mockito.never()).run(any(), anyLong());
    }

    @Test
    void 未接线留痕门_失败仍照抛不炸簿记() {
        when(coreGateway.getExploreSession(SESSION_ID)).thenReturn(runningSession());
        doThrow(new RuntimeException("provider outage"))
                .when(loopKernel).run(any(), eq(7L));

        ExploreRunServiceImpl service = service(false);
        assertThrows(RuntimeException.class,
                () -> service.run(new ExploreRunMessage(SESSION_ID, 35L, 7L)));
    }
}
