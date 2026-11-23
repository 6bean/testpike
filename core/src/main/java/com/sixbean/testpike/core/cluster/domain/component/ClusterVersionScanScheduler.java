package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ClusterBaselineScanResult;
import com.sixbean.testpike.core.cluster.api.dto.ClusterUpstreamProbeResult;
import com.sixbean.testpike.core.cluster.domain.service.ClusterUpstreamProbeService;
import com.sixbean.testpike.core.cluster.domain.service.ClusterVersionBaselineService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Component version baseline scan scheduling (version detection system b, frozen document scan loop).
 * <p>Hook style=same pattern as AppautoFlakySentinelScheduler/MonitorScanScheduler: @Scheduled cron
 * Configurable + @SchedulerLock to prevent concurrent execution across multiple instances + testpike.scheduling.enabled master gate (red line 6 prohibits new frameworks).
 * <p>Cycle key=testpike.cluster.version-check-cron Spring configuration property (bound at startup, migration artifact
 * flaky-sentinel.cron uses the same convention—not a tp_sys_config key, does not trigger pitfall 21 snapshot reconciliation); default daily
 * 03:40 (staggered from the Web flaky 02:30 and APP flaky 03:10 nightly windows, low frequency is sufficient: baseline=descriptor
 * data plane, automatic reconciliation in the next cycle after catalog changes or engine disablement, prohibit high-frequency full-table re-probing every cycle).
 * <p>Failure semantics=scan failure leaves a trace + JOB_FAILED alert wiring (same pattern as JobRunner), self-healing rerun in the next cycle.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ClusterVersionScanScheduler {

    private static final Logger log = LoggerFactory.getLogger(ClusterVersionScanScheduler.class);

    private final ClusterVersionBaselineService baselineService;

    private final ClusterUpstreamProbeService upstreamProbeService;

    private final JobRunner jobRunner;

    public ClusterVersionScanScheduler(ClusterVersionBaselineService baselineService,
                                       ClusterUpstreamProbeService upstreamProbeService,
                                       JobRunner jobRunner) {
        this.baselineService = baselineService;
        this.upstreamProbeService = upstreamProbeService;
        this.jobRunner = jobRunner;
    }

    /** Descriptor baseline reconciliation scan (default daily at 03:40, configurable via testpike.cluster.version-check-cron). */
    @SchedulerLock(name = "clusterVersionBaselineScan", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${testpike.cluster.version-check-cron:0 40 3 * * *}")
    public void syncBaselines() {
        try {
            ClusterBaselineScanResult result = baselineService.syncFromDescriptors();
            if (result.baselinesWritten() > 0) {
                log.info("部件版本基准扫描：在线节点 {} 个，基准回写 {} 行"
                                + "（上游金版本探测=后续包，本回路只刷支持区间基准）",
                        result.nodesScanned(), result.baselinesWritten());
            }
        } catch (Exception e) {
            log.warn("部件版本基准扫描失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("clusterVersionBaselineScan", e);
        }
    }

    /**
     * Upstream golden version probe write-back (default every Monday at 04:10, configurable via testpike.cluster.upstream-probe-cron).
     * <p>Standalone low frequency=upstream latest version changes occur on a weekly cadence, separated by gates from descriptor baseline reconciliation (daily frequency) to prevent per-cycle full-table external calls;
     * Unreachable family=natural gray in the judgment spectrum 'external network probe unreachable' leaves a trace, self-healing in the next cycle.
     */
    @SchedulerLock(name = "clusterUpstreamProbe", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${testpike.cluster.upstream-probe-cron:0 10 4 * * 1}")
    public void probeUpstreamTargets() {
        try {
            ClusterUpstreamProbeResult result = upstreamProbeService.probeTargets();
            if (result.familiesProbed() > 0 || !result.unresolvedNotes().isEmpty()) {
                log.info("上游版本探测：成功 {} 族，target 回写 {} 行，候选落账 {} 版，留痕 {} 条",
                        result.familiesProbed(), result.rowsWritten(),
                        result.candidatesRecorded(), result.unresolvedNotes().size());
            }
        } catch (Exception e) {
            log.warn("上游版本探测失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("clusterUpstreamProbe", e);
        }
    }

    /**
     * Platform version candidate probe (default daily at 04:40, configurable via testpike.cluster.release-check-cron).
     * <p>Standalone low-frequency gate=release feed targets the platform's own release cadence (daily is sufficient), separated by gates from the engine golden baseline
     * weekly probe gate; results persist only to the candidate table (perception plane), release still requires manual confirmation (two-state model red line).
     */
    @SchedulerLock(name = "clusterReleaseCheck", lockAtMostFor = "PT30M")
    @Scheduled(cron = "${testpike.cluster.release-check-cron:0 40 4 * * *}")
    public void releaseCheckCandidates() {
        try {
            ClusterUpstreamProbeResult result = upstreamProbeService.probeReleaseFeed();
            if (result.candidatesRecorded() > 0 || !result.unresolvedNotes().isEmpty()) {
                log.info("平台版本候选探测：新落候选 {} 版，留痕 {} 条",
                        result.candidatesRecorded(), result.unresolvedNotes().size());
            }
        } catch (Exception e) {
            log.warn("平台版本候选探测失败（下轮自动重试）: {}", e.getMessage());
            jobRunner.alert("clusterReleaseCheck", e);
        }
    }
}
