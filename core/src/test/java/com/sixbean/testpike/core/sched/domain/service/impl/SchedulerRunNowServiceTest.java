package com.sixbean.testpike.core.sched.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.domain.component.DailyDigestScheduler;
import com.sixbean.testpike.core.cases.domain.component.FlakyDetectionScheduler;
import com.sixbean.testpike.core.cases.domain.component.ReviewRemindScheduler;
import com.sixbean.testpike.core.cost.domain.component.CostOverrideExpireScheduler;
import com.sixbean.testpike.core.monitor.domain.component.MonitorScanScheduler;
import com.sixbean.testpike.core.executor.domain.component.ExecutorOfflineScheduler;
import com.sixbean.testpike.core.executor.domain.component.TimeoutWatchdogScheduler;
import com.sixbean.testpike.core.project.domain.component.EnvHealthScanScheduler;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportGenerateScheduler;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportRetentionScheduler;
import com.sixbean.testpike.core.retention.domain.component.RetentionCleanupScheduler;
import com.sixbean.testpike.core.sample.domain.component.PromptAdoptionSummaryScheduler;
import com.sixbean.testpike.core.sched.domain.component.ScheduleTriggerScanner;
import com.sixbean.testpike.core.spec.domain.component.SpecSubscriptionScheduler;
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


class SchedulerRunNowServiceTest {

    private final DailyDigestScheduler dailyDigest = mock(DailyDigestScheduler.class);
    private final FlakyDetectionScheduler flakyDetection = mock(FlakyDetectionScheduler.class);
    private final ReviewRemindScheduler reviewRemind = mock(ReviewRemindScheduler.class);
    private final CostOverrideExpireScheduler costOverrideExpire =
            mock(CostOverrideExpireScheduler.class);
    private final ExecutorOfflineScheduler executorOffline = mock(ExecutorOfflineScheduler.class);
    private final TimeoutWatchdogScheduler timeoutWatchdog = mock(TimeoutWatchdogScheduler.class);
    private final EnvHealthScanScheduler envHealthScan = mock(EnvHealthScanScheduler.class);
    private final ReportGenerateScheduler reportGenerate = mock(ReportGenerateScheduler.class);
    private final ReportRetentionScheduler reportRetention = mock(ReportRetentionScheduler.class);
    private final RetentionCleanupScheduler retentionCleanup =
            mock(RetentionCleanupScheduler.class);
    private final PromptAdoptionSummaryScheduler promptAdoption =
            mock(PromptAdoptionSummaryScheduler.class);
    private final ScheduleTriggerScanner triggerScanner = mock(ScheduleTriggerScanner.class);
    private final com.sixbean.testpike.core.spec.domain.component.SpecSubscriptionScheduler specSubscription =
            mock(com.sixbean.testpike.core.spec.domain.component.SpecSubscriptionScheduler.class);
    private final MonitorScanScheduler monitorScan = mock(MonitorScanScheduler.class);
    private final com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseSweepScheduler appautoLeaseSweep =
            mock(com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseSweepScheduler.class);
    private final com.sixbean.testpike.core.appauto.domain.component.AppautoDeviceProbeScheduler appautoDeviceProbe =
            mock(com.sixbean.testpike.core.appauto.domain.component.AppautoDeviceProbeScheduler.class);
    private final com.sixbean.testpike.core.appauto.domain.component.AppautoFlakySentinelScheduler appautoFlakySentinel =
            mock(com.sixbean.testpike.core.appauto.domain.component.AppautoFlakySentinelScheduler.class);
    private final com.sixbean.testpike.core.cluster.domain.component.ClusterVersionScanScheduler clusterVersionScan =
            mock(com.sixbean.testpike.core.cluster.domain.component.ClusterVersionScanScheduler.class);
    private final com.sixbean.testpike.core.cluster.domain.component.UpgradePipelineScheduler upgradePipeline =
            mock(com.sixbean.testpike.core.cluster.domain.component.UpgradePipelineScheduler.class);
    private final com.sixbean.testpike.core.appauto.domain.component.AppautoReservationSweepScheduler appautoReservationSweep =
            mock(com.sixbean.testpike.core.appauto.domain.component.AppautoReservationSweepScheduler.class);
    private final com.sixbean.testpike.core.mock.domain.component.MockSpaceExpirySweeper mockSpaceExpirySweeper =
            mock(com.sixbean.testpike.core.mock.domain.component.MockSpaceExpirySweeper.class);
    private final com.sixbean.testpike.core.mock.domain.component.MockHitRetentionSweeper mockHitRetentionSweeper =
            mock(com.sixbean.testpike.core.mock.domain.component.MockHitRetentionSweeper.class);
    private final com.sixbean.testpike.core.document.domain.component.DocumentExtractStallSweeper docExtractStallSweeper =
            mock(com.sixbean.testpike.core.document.domain.component.DocumentExtractStallSweeper.class);
    private final com.sixbean.testpike.core.cases.domain.component.FlakyProbeScheduler flakyProbe =
            mock(com.sixbean.testpike.core.cases.domain.component.FlakyProbeScheduler.class);
    private final com.sixbean.testpike.core.mcp.domain.component.McpKnowledgeSyncScheduler mcpKnowledgeSync =
            mock(com.sixbean.testpike.core.mcp.domain.component.McpKnowledgeSyncScheduler.class);
    private final com.sixbean.testpike.core.apiaccess.domain.component.ApiEndpointCatalogRefresher
            catalogRefresher =
            mock(com.sixbean.testpike.core.apiaccess.domain.component.ApiEndpointCatalogRefresher.class);
    private final com.sixbean.testpike.core.deadletter.domain.component.DeadLetterAutoReplayScheduler
            deadLetterAutoReplay =
            mock(com.sixbean.testpike.core.deadletter.domain.component.DeadLetterAutoReplayScheduler.class);
    private final com.sixbean.testpike.core.linkage.domain.component.ChangeLinkageReconcileJob
            changeLinkageReconcileJob =
            mock(com.sixbean.testpike.core.linkage.domain.component.ChangeLinkageReconcileJob.class);
    private final com.sixbean.testpike.core.linkage.domain.component.LinkageUrgeScheduler linkageUrge =
            mock(com.sixbean.testpike.core.linkage.domain.component.LinkageUrgeScheduler.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);

    private final com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService
            webElementRetention =
            mock(com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService.class);
    private final com.sixbean.testpike.core.executor.domain.component.ExecutorDrainSweepScheduler
            executorDrainSweep =
            mock(com.sixbean.testpike.core.executor.domain.component.ExecutorDrainSweepScheduler.class);
    private final com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimScheduler
            aiTaskEventTrim =
            mock(com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimScheduler.class);
    private final com.sixbean.testpike.core.session.domain.component.LoginStateRetentionSweeper
            loginStateRetention =
            mock(com.sixbean.testpike.core.session.domain.component.LoginStateRetentionSweeper.class);
    private final com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolWatermarkSampler
            executorPoolWatermarkSampler =
            mock(com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolWatermarkSampler.class);
    private final com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveScheduler
            loginStateKeepAlive =
            mock(com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveScheduler.class);
    private final com.sixbean.testpike.core.ai.domain.component.AiTaskRunningStallSweeper
            aiTaskRunningStallSweeper =
            mock(com.sixbean.testpike.core.ai.domain.component.AiTaskRunningStallSweeper.class);
    private final com.sixbean.testpike.core.webelement.domain.component.SiteSurveyAttributionSweepJob
            siteSurveyAttributionSweep =
            mock(com.sixbean.testpike.core.webelement.domain.component.SiteSurveyAttributionSweepJob.class);
    private final com.sixbean.testpike.core.aichat.domain.component.CredentialScreenshotSweepScheduler
            credentialScreenshotSweep =
            mock(com.sixbean.testpike.core.aichat.domain.component.CredentialScreenshotSweepScheduler.class);

    private final SchedulerRunNowServiceImpl service = new SchedulerRunNowServiceImpl(
            dailyDigest, flakyDetection, flakyProbe, reviewRemind, costOverrideExpire, executorOffline,
            timeoutWatchdog, envHealthScan, reportGenerate, reportRetention, retentionCleanup,
            promptAdoption, triggerScanner, specSubscription, monitorScan, appautoLeaseSweep,
            appautoDeviceProbe, appautoFlakySentinel, clusterVersionScan, upgradePipeline,
            appautoReservationSweep, mockSpaceExpirySweeper, mockHitRetentionSweeper,
            docExtractStallSweeper, mcpKnowledgeSync, catalogRefresher, deadLetterAutoReplay,
            changeLinkageReconcileJob, linkageUrge, webElementRetention,
            executorDrainSweep, aiTaskEventTrim, loginStateRetention,
            executorPoolWatermarkSampler, loginStateKeepAlive, aiTaskRunningStallSweeper,
            siteSurveyAttributionSweep, credentialScreenshotSweep, auditLogService);

    @Test
    void webElementLedgerRetentionJobRoutesToItsSweep() {
        service.run("webElementLedgerRetentionDaily", 7L);
        verify(webElementRetention).sweep();
    }

    @Test
    void linkageUrgeJobRoutesToItsScanMethod() {
        service.run("linkageUrgeHourly", 7L);
        verify(linkageUrge).scanHourly();
    }

    @Test
    void deadLetterAutoReplayJobRoutesToItsSweepMethod() {
        service.run("dlqAutoReplay", 7L);
        verify(deadLetterAutoReplay).sweep();
    }

    @Test
    void changeLinkageReconcileJobRoutesToItsReconcileMethod() {
        service.run("changeLinkageReconcileDaily", 7L);
        verify(changeLinkageReconcileJob).reconcile();
    }

    @Test
    void knownJobRoutesToItsScheduledMethodWithAudit() {
        service.run("flakyDetectionDaily", 42L);
        verify(flakyDetection).detectDaily();

        ArgumentCaptor<AuditLogInput> captor = ArgumentCaptor.forClass(AuditLogInput.class);
        verify(auditLogService).record(captor.capture());
        AuditLogInput audit = captor.getValue();
        assertEquals(42L, audit.operatorId(), "run-now 审计必须记录操作人（X-Operator-Id 透传）");
        assertEquals("scheduler", audit.objectType());
        assertEquals("flakyDetectionDaily", audit.objectId());
        assertEquals("RUN_NOW", audit.action());
    }

    @Test
    void clusterVersionJobsRouteToScanSchedulerDistinctMethods() {
        service.run("clusterVersionBaselineScan", 7L);
        verify(clusterVersionScan).syncBaselines();
        service.run("clusterUpstreamProbe", 7L);
        verify(clusterVersionScan).probeUpstreamTargets();
    }

    @Test
    void weeklyAndMonthlyReportJobsRouteToDistinctMethods() {
        service.run("reportWeeklyGenerate", 1L);
        verify(reportGenerate).generateWeekly();
        service.run("reportMonthlyGenerate", 1L);
        verify(reportGenerate).generateMonthly();
    }

    @Test
    void unknownJobRejectedWithHumanMessageAndZeroExecution() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.run("不存在的任务", 1L));
        assertTrue(e.getMessage().contains("不存在的任务"),
                "人话消息必须带未知任务名便于定位: " + e.getMessage());
        verifyNoInteractions(auditLogService);
        verifyNoInteractions(dailyDigest, flakyDetection, reviewRemind, costOverrideExpire,
                executorOffline, timeoutWatchdog, envHealthScan, reportGenerate,
                reportRetention, retentionCleanup, promptAdoption, triggerScanner,
                specSubscription, monitorScan);
    }

    @Test
    void blankJobNameRejected() {
        assertThrows(ServiceException.class, () -> service.run("  ", 1L));
        assertThrows(ServiceException.class, () -> service.run(null, 1L));
    }

    @Test
    void jobFailureSurfacesToCallerAndAuditsFailure() {
        doThrow(new IllegalStateException("db down")).when(envHealthScan).scan();
        ServiceException e = assertThrows(ServiceException.class,
                () -> service.run("envHealthScan", 7L));
        assertTrue(e.getMessage().contains("db down"),
                "失败原因必须上浮给调用方（人话）: " + e.getMessage());

        ArgumentCaptor<AuditLogInput> captor = ArgumentCaptor.forClass(AuditLogInput.class);
        verify(auditLogService).record(captor.capture());
        assertEquals("RUN_NOW_FAILED", captor.getValue().action());
    }

    @Test
    void everyCoreSchedulerLockNameIsRegistered() throws IOException {
        List<Path> sources = new ArrayList<>();
        collectJavaSources(Path.of("src/main/java"), sources);
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
        assertTrue(total >= 13, "core @SchedulerLock 注解面应 ≥13（实查 " + total + "）");
        assertTrue(missing.isEmpty(), "core 调度任务未登记 run-now 注册表: " + missing);
    }

    private void collectJavaSources(Path tree, List<Path> out) throws IOException {
        if (!Files.isDirectory(tree)) {
            return;
        }
        try (var paths = Files.walk(tree)) {
            paths.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> !p.toString().contains("/component/JobRunner"))
                    .forEach(out::add);
        }
    }
}
