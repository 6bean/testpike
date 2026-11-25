package com.sixbean.testpike.core.sched.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.appauto.domain.component.AppautoDeviceProbeScheduler;
import com.sixbean.testpike.core.appauto.domain.component.AppautoFlakySentinelScheduler;
import com.sixbean.testpike.core.appauto.domain.component.AppautoLeaseSweepScheduler;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.cases.domain.component.DailyDigestScheduler;
import com.sixbean.testpike.core.cases.domain.component.FlakyDetectionScheduler;
import com.sixbean.testpike.core.cases.domain.component.FlakyProbeScheduler;
import com.sixbean.testpike.core.cases.domain.component.ReviewRemindScheduler;
import com.sixbean.testpike.core.cost.domain.component.CostOverrideExpireScheduler;
import com.sixbean.testpike.core.executor.domain.component.ExecutorOfflineScheduler;
import com.sixbean.testpike.core.executor.domain.component.TimeoutWatchdogScheduler;
import com.sixbean.testpike.core.monitor.domain.component.MonitorScanScheduler;
import com.sixbean.testpike.core.project.domain.component.EnvHealthScanScheduler;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportGenerateScheduler;
import com.sixbean.testpike.core.reportcenter.domain.component.ReportRetentionScheduler;
import com.sixbean.testpike.core.retention.domain.component.RetentionCleanupScheduler;
import com.sixbean.testpike.core.sample.domain.component.PromptAdoptionSummaryScheduler;
import com.sixbean.testpike.core.sched.domain.component.ScheduleTriggerScanner;
import com.sixbean.testpike.core.sched.domain.service.SchedulerRunNowService;
import com.sixbean.testpike.core.spec.domain.component.SpecSubscriptionScheduler;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


@Service
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulerRunNowServiceImpl implements SchedulerRunNowService {

    private final Map<String, Runnable> registry;
    private final AuditLogService auditLogService;

    public SchedulerRunNowServiceImpl(DailyDigestScheduler dailyDigest,
                                      FlakyDetectionScheduler flakyDetection,
                                      FlakyProbeScheduler flakyProbe,
                                      ReviewRemindScheduler reviewRemind,
                                      CostOverrideExpireScheduler costOverrideExpire,
                                      ExecutorOfflineScheduler executorOffline,
                                      TimeoutWatchdogScheduler timeoutWatchdog,
                                      EnvHealthScanScheduler envHealthScan,
                                      ReportGenerateScheduler reportGenerate,
                                      ReportRetentionScheduler reportRetention,
                                      RetentionCleanupScheduler retentionCleanup,
                                      PromptAdoptionSummaryScheduler promptAdoption,
                                      ScheduleTriggerScanner triggerScanner,
                                      SpecSubscriptionScheduler specSubscription,
                                      MonitorScanScheduler monitorScan,
                                      AppautoLeaseSweepScheduler appautoLeaseSweep,
                                      AppautoDeviceProbeScheduler appautoDeviceProbe,
                                      AppautoFlakySentinelScheduler appautoFlakySentinel,
                                      com.sixbean.testpike.core.cluster.domain.component.ClusterVersionScanScheduler clusterVersionScan,
                                      com.sixbean.testpike.core.cluster.domain.component.UpgradePipelineScheduler upgradePipeline,
                                      com.sixbean.testpike.core.appauto.domain.component.AppautoReservationSweepScheduler appautoReservationSweep,
                                      com.sixbean.testpike.core.mock.domain.component.MockSpaceExpirySweeper mockSpaceExpirySweeper,
                                      com.sixbean.testpike.core.mock.domain.component.MockHitRetentionSweeper mockHitRetentionSweeper,
                                      com.sixbean.testpike.core.document.domain.component.DocumentExtractStallSweeper docExtractStallSweeper,
                                      com.sixbean.testpike.core.mcp.domain.component.McpKnowledgeSyncScheduler mcpKnowledgeSync,
                                      com.sixbean.testpike.core.apiaccess.domain.component.ApiEndpointCatalogRefresher catalogRefresher,
                                      com.sixbean.testpike.core.deadletter.domain.component.DeadLetterAutoReplayScheduler deadLetterAutoReplay,
                                      com.sixbean.testpike.core.linkage.domain.component.ChangeLinkageReconcileJob changeLinkageReconcileJob,
                                      com.sixbean.testpike.core.linkage.domain.component.LinkageUrgeScheduler linkageUrge,
                                      com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService webElementRetention,
                                      com.sixbean.testpike.core.executor.domain.component.ExecutorDrainSweepScheduler executorDrainSweep,
                                      com.sixbean.testpike.core.events.domain.component.AiTaskEventTrimScheduler aiTaskEventTrim,
                                      com.sixbean.testpike.core.session.domain.component.LoginStateRetentionSweeper loginStateRetention,
                                      com.sixbean.testpike.core.cluster.domain.component.ExecutorPoolWatermarkSampler executorPoolWatermarkSampler,
                                      com.sixbean.testpike.core.session.domain.component.LoginStateKeepAliveScheduler loginStateKeepAlive,
                                      com.sixbean.testpike.core.ai.domain.component.AiTaskRunningStallSweeper aiTaskRunningStallSweeper,
                                      com.sixbean.testpike.core.webelement.domain.component.SiteSurveyAttributionSweepJob siteSurveyAttributionSweep,
                                      com.sixbean.testpike.core.aichat.domain.component.CredentialScreenshotSweepScheduler credentialScreenshotSweep,
                                      com.sixbean.testpike.core.mock.domain.component.SpecImportVerdictRetentionScheduler specImportVerdictTrim,
                                      AuditLogService auditLogService) {
        this.auditLogService = auditLogService;
        this.registry = new LinkedHashMap<>();
        registry.put("dailyDigestScan", dailyDigest::scanDaily);
        registry.put("flakyDetectionDaily", flakyDetection::detectDaily);
        registry.put("flakyProbeSweep", flakyProbe::sweep);
        registry.put("reviewRemindHourly", reviewRemind::scanHourly);
        registry.put("costOverrideExpire", costOverrideExpire::cleanExpiredOverrides);
        registry.put("executorOfflineSweep", executorOffline::sweepOffline);
        registry.put("execTimeoutWatchdog", timeoutWatchdog::sweep);
        registry.put("envHealthScan", envHealthScan::scan);
        registry.put("certExpiryScan", envHealthScan::scanCertExpiry);
        registry.put("reportWeeklyGenerate", reportGenerate::generateWeekly);
        registry.put("reportMonthlyGenerate", reportGenerate::generateMonthly);
        registry.put("reportRetentionDaily", reportRetention::purgeExpired);
        registry.put("webElementLedgerRetentionDaily", webElementRetention::sweep);
        registry.put("executorDrainSweep", executorDrainSweep::sweepStaleDrains);
        registry.put("aiTaskEventTrim", aiTaskEventTrim::sweep);
        registry.put("loginStateRetentionSweep", loginStateRetention::sweep);
        registry.put("retentionCleanupDaily", retentionCleanup::cleanupDaily);
        registry.put("promptAdoptionSummaryDaily", promptAdoption::summarizeDaily);
        registry.put("scheduleTriggerScan", triggerScanner::scanMinute);
        registry.put("specSubscriptionScan", specSubscription::scanMinute);
        registry.put("mcpKnowledgeSyncScan", mcpKnowledgeSync::scanMinute);
        registry.put("monitorScan", monitorScan::scan);
        registry.put("monitorLostWatchdog", monitorScan::lostWatchdog);
        registry.put("monitorEnvVersionRecheck", monitorScan::recheckEnvVersions);
        registry.put("appautoLeaseSweep", appautoLeaseSweep::sweepExpiredDeviceLeases);
        registry.put("appautoDeviceProbe", appautoDeviceProbe::probeRemoteDevicesOnce);
        registry.put("appautoFlakySentinelDaily", appautoFlakySentinel::detectDaily);
        registry.put("clusterVersionBaselineScan", clusterVersionScan::syncBaselines);
        registry.put("clusterUpstreamProbe", clusterVersionScan::probeUpstreamTargets);
        registry.put("upgradePipelineAdvance", upgradePipeline::advance);
        registry.put("clusterReleaseCheck", clusterVersionScan::releaseCheckCandidates);
        registry.put("appautoReservationSweep", appautoReservationSweep::sweepExpiredReservations);
        registry.put("mockSpaceExpirySweep", mockSpaceExpirySweeper::sweepExpiredSpaces);
        registry.put("mockHitSweep", mockHitRetentionSweeper::purgeExpiredHits);
        registry.put("docExtractStallSweep", docExtractStallSweeper::sweepStalled);
        registry.put("apiAccessCatalogRefresh", catalogRefresher::refreshCatalog);
        registry.put("dlqAutoReplay", deadLetterAutoReplay::sweep);
        registry.put("changeLinkageReconcileDaily", changeLinkageReconcileJob::reconcile);
        registry.put("linkageUrgeHourly", linkageUrge::scanHourly);
        // Fresh-install baseline repair: every @SchedulerLock name in core must
        // be triggerable from the run-now registry (contract test pins this).
        registry.put("clusterPoolWatermarkSample", executorPoolWatermarkSampler::samplePools);
        registry.put("loginStateKeepAlive", loginStateKeepAlive::tick);
        registry.put("aiTaskRunningStallSweep", aiTaskRunningStallSweeper::sweep);
        registry.put("siteSurveyAttributionSweep", siteSurveyAttributionSweep::sweep);
        registry.put("credentialScreenshotSweep", credentialScreenshotSweep::sweep);
        registry.put("mockSpecImportVerdictTrim", specImportVerdictTrim::sweep);
    }

    @Override
    public void run(String jobName, Long operatorId) {
        if (jobName == null || jobName.isBlank()) {
            throw new ServiceException("任务名不能为空");
        }
        Runnable job = registry.get(jobName.trim());
        if (job == null) {
            throw new ServiceException("未知的定时任务：" + jobName.trim()
                    + "（可选任务：" + String.join("、", registry.keySet()) + "）");
        }
        try {
            job.run();
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            recordAudit(operatorId, jobName.trim(), "RUN_NOW_FAILED", reason);
            throw new ServiceException("定时任务 " + jobName.trim() + " 手动执行失败：" + reason);
        }
        recordAudit(operatorId, jobName.trim(), "RUN_NOW", null);
    }

    @Override
    public Set<String> jobNames() {
        return Set.copyOf(registry.keySet());
    }

    private void recordAudit(Long operatorId, String jobName, String action, String reason) {
        Map<String, Object> after = reason == null
                ? Map.of("mode", "manual-run-now")
                : Map.of("mode", "manual-run-now", "reason", reason);
        auditLogService.record(new AuditLogInput(operatorId, null, "scheduler", jobName,
                action, null, after));
    }
}
