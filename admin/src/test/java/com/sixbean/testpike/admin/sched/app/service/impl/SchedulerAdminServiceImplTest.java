package com.sixbean.testpike.admin.sched.app.service.impl;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;
import com.sixbean.testpike.admin.sched.infrastructure.remote.client.SchedulerAiClient;
import com.sixbean.testpike.admin.sched.infrastructure.remote.client.SchedulerCoreClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class SchedulerAdminServiceImplTest {

    private final SchedulerCoreClient coreClient = mock(SchedulerCoreClient.class);
    private final SchedulerAiClient aiClient = mock(SchedulerAiClient.class);
    private final SchedulerAdminServiceImpl service =
            new SchedulerAdminServiceImpl(coreClient, aiClient);

    @Test
    void jobsMergeCoreAndAiRegistries() {
        when(coreClient.jobs()).thenReturn(Set.of("dailyDigestScan", "envHealthScan"));
        when(aiClient.jobs()).thenReturn(Set.of("knowledgeReconcileDaily"));
        List<String> merged = service.jobs();
        assertTrue(merged.containsAll(
                List.of("dailyDigestScan", "envHealthScan", "knowledgeReconcileDaily")));
        assertEquals(3, merged.size());
    }

    @Test
    void aiServiceJobRoutesToAiLegWithOperator() {
        when(aiClient.jobs()).thenReturn(Set.of("usageCalibrationRefresh"));
        service.run("usageCalibrationRefresh", 9L);
        verify(aiClient).runNow(eq("usageCalibrationRefresh"), eq(9L));
        verifyNoInteractions(coreClient);
    }

    @Test
    void coreJobRoutesToCoreLeg() {
        when(aiClient.jobs()).thenReturn(Set.of("usageCalibrationRefresh"));
        service.run("envHealthScan", 9L);
        verify(coreClient).runNow("envHealthScan", 9L);
    }

    @Test
    void unknownJobFallsThroughToCoreHumanRejection() {
        when(aiClient.jobs()).thenReturn(Set.of("usageCalibrationRefresh"));
        when(coreClient.runNow(eq("不存在"), anyLong()))
                .thenThrow(new ServiceException("未知的定时任务：不存在（可选任务：…）", 500));
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.run("不存在", 9L));
        assertTrue(e.getMessage().contains("未知的定时任务"),
                "core 人话拒单消息必须原样上浮: " + e.getMessage());
    }

    @Test
    void healthDelegatesToCoreSingleSource() {
        PageView<SchedulerHealthView> page = new PageView<>(
                List.of(new SchedulerHealthView("dailyDigestScan", "每日用例数据摘要统计",
                        "host-1", "2026-09-28 02:00:00", "2026-09-28 04:00:00", true)),
                15L);
        when(coreClient.health("摘要", 1, 20, "job_name", "asc")).thenReturn(page);
        PageView<SchedulerHealthView> got = service.health("摘要", 1, 20, "job_name", "asc");
        assertEquals(15L, got.total());
        assertTrue(got.list().get(0).running());
        verify(coreClient).health("摘要", 1, 20, "job_name", "asc");
        verifyNoInteractions(aiClient);
    }

    @Test
    void healthSortPairPassesThroughUnmodified() {
        // admin is a pure bridge: sort key/direction forwarded untouched, null
        // keeps the core catalog order — no default rewriting on this side.
        PageView<SchedulerHealthView> empty = new PageView<>(List.of(), 0L);
        when(coreClient.health(null, 1, 20, null, null)).thenReturn(empty);
        service.health(null, 1, 20, null, null);
        verify(coreClient).health(null, 1, 20, null, null);

        when(coreClient.health(null, 2, 50, "locked_at", "desc")).thenReturn(empty);
        service.health(null, 2, 50, "locked_at", "desc");
        verify(coreClient).health(null, 2, 50, "locked_at", "desc");
    }
}
