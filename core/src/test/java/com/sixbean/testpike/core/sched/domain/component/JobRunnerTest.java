package com.sixbean.testpike.core.sched.domain.component;

import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;


class JobRunnerTest {

    private final NotificationService notificationService = mock(NotificationService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminAlertRecipients adminAlertRecipients = mock(AdminAlertRecipients.class);
    private final JobRunner jobRunner =
            new JobRunner(notificationService, auditLogService, adminAlertRecipients);

    @Test
    void failureAlertFansOutPerAdminWithHourDedupAndAudit() {
        when(adminAlertRecipients.adminUserIds()).thenReturn(List.of(7L, 8L));
        assertDoesNotThrow(() -> jobRunner.alert("execTimeoutWatchdog",
                new IllegalStateException("db down")));
        String hourWindow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        ArgumentCaptor<NotificationCreateInput> captor =
                ArgumentCaptor.forClass(NotificationCreateInput.class);
        verify(notificationService, times(2)).create(captor.capture());
        List<NotificationCreateInput> sent = captor.getAllValues();
        assertEquals(List.of(7L, 8L), sent.stream().map(NotificationCreateInput::userId).toList(),
                "告警必须扇出到每个系统管理员");
        for (NotificationCreateInput n : sent) {
            assertEquals("JOB_FAILED", n.eventType());
            assertTrue(n.title().contains("定时任务"), "标题人话中文: " + n.title());
            assertTrue(n.content().contains("execTimeoutWatchdog"), "内容含任务名: " + n.content());
            assertTrue(n.content().contains("db down"), "内容含失败原因不截断: " + n.content());
            assertTrue(n.dedupKey().startsWith("JOB_FAILED:execTimeoutWatchdog:" + hourWindow),
                    "dedup=任务名+小时窗防刷屏: " + n.dedupKey());
            assertTrue(n.dedupKey().endsWith(":" + n.userId()),
                    "dedup 含接收人，防服务层同键跨用户合并吞掉后续管理员: " + n.dedupKey());
        }
        ArgumentCaptor<AuditLogInput> audit = ArgumentCaptor.forClass(AuditLogInput.class);
        verify(auditLogService).record(audit.capture());
        assertEquals("JOB_FAILED", audit.getValue().action());
        assertEquals("execTimeoutWatchdog", audit.getValue().objectId());
    }

    @Test
    void nullMessageErrorStillAlerts() {
        when(adminAlertRecipients.adminUserIds()).thenReturn(List.of(7L));
        jobRunner.alert("dailyDigestScan", new IllegalStateException());
        ArgumentCaptor<NotificationCreateInput> captor =
                ArgumentCaptor.forClass(NotificationCreateInput.class);
        verify(notificationService).create(captor.capture());
        assertTrue(captor.getValue().content().contains("IllegalStateException"),
                "message 为 null 时回落异常类名，禁出现 null 字样: " + captor.getValue().content());
    }

    @Test
    void alertLegChannelFailureNeverEscapes() {
        when(adminAlertRecipients.adminUserIds()).thenReturn(List.of(7L, 8L));
        doThrow(new RuntimeException("notify channel broken"))
                .when(notificationService).create(any(NotificationCreateInput.class));
        assertDoesNotThrow(() -> jobRunner.alert("knowledgeReconcileDaily",
                new IllegalStateException("original job failure")));
        verify(notificationService, times(2)).create(any(NotificationCreateInput.class));
    }

    @Test
    void recipientLookupFailureNeverEscapes() {
        when(adminAlertRecipients.adminUserIds()).thenThrow(new RuntimeException("db unreachable"));
        assertDoesNotThrow(() -> jobRunner.alert("envHealthScan", new RuntimeException("boom")));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void everyCoreSchedulerIsWiredToJobRunner() throws IOException {
        Path tree = locateRepoRoot().resolve("core/src/main/java/com/sixbean/testpike/core");
        List<Path> schedulers;
        try (var walk = Files.walk(tree)) {
            schedulers = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(JobRunnerTest::hasScheduledAnnotation)
                    .toList();
        }
        // 27 core scheduler classes since the document-extract stall sweeper, the MCP knowledge-sync
        // scheduler, the flaky-probe scheduler, the interface-catalog refresher and the dead-letter
        // auto-replay scheduler joined (each wires jobRunner; the correction-distillation scheduler
        // lives in ai-service and is out of this core-tree count by design).
        assertEquals(27, schedulers.size(),
                "core 含 @Scheduled 的调度类文件数变化=调度器扩缩，同步核对告警接线后更新期望值");
        List<Path> unwired = new ArrayList<>();
        for (Path p : schedulers) {
            try {
                if (!Files.readString(p).contains("jobRunner")) {
                    unwired.add(p);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
        assertTrue(unwired.isEmpty(), "以下调度器未接 JobRunner 告警链路: " + unwired);
    }

    
    private static boolean hasScheduledAnnotation(Path file) {
        try {
            return Files.readAllLines(file).stream()
                    .anyMatch(l -> l.trim().startsWith("@Scheduled"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Path locateRepoRoot() {
        return com.sixbean.testpike.core.support.RepoRootLocator.locate();
    }
}
