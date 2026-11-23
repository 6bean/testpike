package com.sixbean.testpike.aiservice.sched.domain.service.impl;

import com.sixbean.testpike.aiservice.aictx.domain.component.CtxDistillScheduler;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeReconcileScheduler;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.UsageCalibrationRefresher;
import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;


class AiSchedulerRunNowServiceTest {

    private final KnowledgeReconcileScheduler reconcileScheduler =
            mock(KnowledgeReconcileScheduler.class);
    private final UsageCalibrationRefresher usageRefresher = mock(UsageCalibrationRefresher.class);
    private final CtxDistillScheduler distillScheduler = mock(CtxDistillScheduler.class);
    private final CoreGateway coreGateway = mock(CoreGateway.class);
    private final com.sixbean.testpike.aiservice.llm.domain.component.LlmCapabilityReprobeScheduler
            capabilityReprobeScheduler = mock(
            com.sixbean.testpike.aiservice.llm.domain.component.LlmCapabilityReprobeScheduler.class);

    private final AiSchedulerRunNowServiceImpl service = new AiSchedulerRunNowServiceImpl(
            reconcileScheduler, usageRefresher, distillScheduler, capabilityReprobeScheduler, coreGateway);

    @Test
    void knownJobRoutesToItsScheduledMethodWithAudit() {
        service.run("knowledgeReconcileDaily", 42L);
        verify(reconcileScheduler).reconcileDaily();

        ArgumentCaptor<LlmAuditRequest> captor = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(captor.capture());
        LlmAuditRequest audit = captor.getValue();
        assertEquals(42L, audit.operatorId(), "run-now 审计必须记录操作人（透传）");
        assertEquals("scheduler", audit.objectType());
        assertEquals("knowledgeReconcileDaily", audit.objectId());
        assertEquals("RUN_NOW", audit.action());
    }

    @Test
    void capabilityReprobeJobRoutesToItsScheduledRound() {
        // Ticket-② the reprobe scheduler's manual channel must reach
        // the same scheduled round the cron drives (L3 acceptance anchor).
        service.run("llmCapabilityReprobe", 7L);
        verify(capabilityReprobeScheduler).reprobeHourly();
    }

    @Test
    void unknownJobRejectedWithHumanMessageAndZeroExecution() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.run("不存在", 1L));
        assertTrue(e.getMessage().contains("不存在"), "人话消息带未知任务名: " + e.getMessage());
        verifyNoInteractions(coreGateway);
        verifyNoInteractions(reconcileScheduler, usageRefresher);
    }

    @Test
    void blankJobNameRejected() {
        assertThrows(ServiceException.class, () -> service.run("  ", 1L));
        assertThrows(ServiceException.class, () -> service.run(null, 1L));
    }

    @Test
    void jobFailureSurfacesToCallerAndAuditsFailure() {
        doThrow(new IllegalStateException("llm down")).when(usageRefresher).refresh();
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.run("usageCalibrationRefresh", 7L));
        assertTrue(e.getMessage().contains("llm down"),
                "失败原因必须上浮给调用方: " + e.getMessage());
        ArgumentCaptor<LlmAuditRequest> captor = ArgumentCaptor.forClass(LlmAuditRequest.class);
        verify(coreGateway).writeAudit(captor.capture());
        assertEquals("RUN_NOW_FAILED", captor.getValue().action());
    }

    @Test
    void everyAiServiceSchedulerLockNameIsRegistered() throws IOException {
        List<Path> sources = new ArrayList<>();
        try (var paths = Files.walk(Path.of("src/main/java"))) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(sources::add);
        }
        Pattern lockName = Pattern.compile("@SchedulerLock\\s*\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
        List<String> missing = new ArrayList<>();
        int total = 0;
        for (Path source : sources) {
            Matcher m = lockName.matcher(Files.readString(source));
            while (m.find()) {
                total++;
                if (!service.jobNames().contains(m.group(1))) {
                    missing.add(m.group(1));
                }
            }
        }
        assertEquals(4, total, "ai-service @SchedulerLock 注解面应为 4（实查 " + total + "）"
                + "（第 4 枚=能力复探调度器 llmCapabilityReprobe，11-20 本包新增）");
        assertTrue(missing.isEmpty(), "ai-service 调度任务未登记 run-now 注册表: " + missing);
    }
}
