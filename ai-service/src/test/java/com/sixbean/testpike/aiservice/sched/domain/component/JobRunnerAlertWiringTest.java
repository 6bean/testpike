package com.sixbean.testpike.aiservice.sched.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class JobRunnerAlertWiringTest {

    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final JobRunner jobRunner = new JobRunner(coreGateway);

    @Test
    void failureAlertFansOutPerAdminWithHourDedup() {
        when(coreGateway.listAdminRecipients()).thenReturn(List.of(7L, 8L));
        assertDoesNotThrow(() -> jobRunner.alert("knowledgeReconcileDaily",
                new IllegalStateException("embed endpoint down")));
        String hourWindow = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHH"));
        ArgumentCaptor<Long> userIds = ArgumentCaptor.forClass(Long.class);
        ArgumentCaptor<String> eventTypes = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> titles = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> dedups = ArgumentCaptor.forClass(String.class);
        verify(coreGateway, times(2)).createNotification(userIds.capture(), isNull(),
                eventTypes.capture(), titles.capture(), contents.capture(), dedups.capture());
        assertEquals(List.of(7L, 8L), userIds.getAllValues(), "告警必须扇出到每个系统管理员");
        for (int i = 0; i < 2; i++) {
            assertEquals("JOB_FAILED", eventTypes.getAllValues().get(i));
            String title = titles.getAllValues().get(i);
            String content = contents.getAllValues().get(i);
            String dedup = dedups.getAllValues().get(i);
            assertTrue(title.contains("定时任务"), "标题人话中文: " + title);
            assertTrue(content.contains("knowledgeReconcileDaily"), "内容含任务名: " + content);
            assertTrue(content.contains("embed endpoint down"), "内容含失败原因不截断: " + content);
            assertTrue(dedup.startsWith("JOB_FAILED:knowledgeReconcileDaily:" + hourWindow),
                    "dedup=任务名+小时窗防刷屏: " + dedup);
            assertTrue(dedup.endsWith(":" + userIds.getAllValues().get(i)),
                    "dedup 含接收人，防服务层同键跨用户合并吞掉后续管理员: " + dedup);
        }
    }

    @Test
    void nullMessageErrorFallsBackToClassName() {
        when(coreGateway.listAdminRecipients()).thenReturn(List.of(7L));
        jobRunner.alert("usageCalibrationRefresh", new IllegalStateException());
        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        verify(coreGateway).createNotification(anyLong(), isNull(), eq("JOB_FAILED"),
                anyString(), contents.capture(), anyString());
        String content = contents.getValue();
        assertTrue(content.contains("IllegalStateException"),
                "message 为 null 时回落异常类名: " + content);
        assertTrue(!content.contains("null"), "禁出现 null 字样: " + content);
    }

    @Test
    void alertLegFailureNeverEscapes() {
        when(coreGateway.listAdminRecipients()).thenReturn(List.of(7L, 8L));
        doThrow(new RuntimeException("core unreachable"))
                .when(coreGateway).createNotification(anyLong(), isNull(), anyString(),
                        anyString(), anyString(), anyString());
        assertDoesNotThrow(() -> jobRunner.alert("knowledgeReconcileDaily",
                new IllegalStateException("original job failure")),
                "通知链路故障绝不外溢——调度下一轮不受影响");
        verify(coreGateway, times(2)).createNotification(anyLong(), isNull(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void recipientLookupFailureNeverEscapes() {
        when(coreGateway.listAdminRecipients()).thenThrow(new RuntimeException("db down"));
        assertDoesNotThrow(() -> jobRunner.alert("usageCalibrationRefresh",
                new RuntimeException("boom")));
    }

    
    @Test
    void everyAiSchedulerIsWiredToJobRunner() throws IOException {
        Path tree = locateRepoRoot().resolve("ai-service/src/main/java");
        List<Path> schedulers;
        try (var walk = Files.walk(tree)) {
            schedulers = walk.filter(p -> p.toString().endsWith(".java"))
                    .filter(JobRunnerAlertWiringTest::hasScheduledAnnotation)
                    .toList();
        }
        assertEquals(4, schedulers.size(),
                "ai-service 含 @Scheduled 的调度类文件数变化=调度器扩缩，同步核对告警接线后更新期望值");
        List<Path> unwired = new ArrayList<>();
        for (Path p : schedulers) {
            if (!Files.readString(p).contains("jobRunner")) {
                unwired.add(p);
            }
        }
        assertTrue(unwired.isEmpty(), "以下调度器未接告警链路: " + unwired);
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
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isDirectory(dir.resolve("core")) && Files.isDirectory(dir.resolve("ai-service"))) {
                return dir;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未能上溯定位仓库根");
    }
}
