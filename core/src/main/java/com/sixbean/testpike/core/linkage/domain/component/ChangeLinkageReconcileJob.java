package com.sixbean.testpike.core.linkage.domain.component;

import com.sixbean.testpike.core.linkage.api.dto.LinkageReconcileSummary;
import com.sixbean.testpike.core.linkage.domain.service.OrphanEdgeScanService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily bus-edge reconciliation backstop (AN batch , segment A segment 4;
 * KnowledgeReconcileScheduler / DocumentExtractStallSweeper precedent).
 * <p>Why this class exists: the change-linkage loop is event-driven — saving
 * an upstream object publishes {@code changed:{objectType}} and edge
 * detectors consume it. A missed publish (producer crash between commit and
 * AfterCommit dispatch, consumer queue loss, schema drift nobody registered)
 * otherwise stays invisible until a user acts on stale downstream data. This
 * job re-runs the orphan-edge scan every night so structural drift surfaces
 * within one day even when no event ever fires.
 * <p>Posture notes:
 * <ul>
 * <li>report-only: the job reads and counts, it NEVER writes ledger or
 * business rows — closing a finding means registering/disabling an
 * edge through the normal edge CRUD, and content changes still ride
 * the group-card one-click path (red line: auto-marking yes,
 * auto-content no);</li>
 * <li>the scan service is the single verdict source; the summary DTO only
 * adds the aggregate count (no second reconciliation logic here);</li>
 * <li>exceptions are caught and alerted through JobRunner so one bad night
 * never stops the schedule (idempotent read-only body makes the next
 * round a safe retry);</li>
 * <li>cron key {@code testpike.linkage.reconcile-cron} (factory 03:45,
 * clear of flaky 02:30 / retention 03:30 / knowledge 02:00 slots);
 * the business entry {@link #reconcile} is shared with the run-now
 * picker and the tests.</li>
 * </ul>
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ChangeLinkageReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(ChangeLinkageReconcileJob.class);

    /** Scheduler lock name; must stay identical to the catalog/registry key. */
    public static final String JOB_NAME = "changeLinkageReconcileDaily";

    private final OrphanEdgeScanService orphanEdgeScanService;
    private final JobRunner jobRunner;

    public ChangeLinkageReconcileJob(OrphanEdgeScanService orphanEdgeScanService,
                                     JobRunner jobRunner) {
        this.orphanEdgeScanService = orphanEdgeScanService;
        this.jobRunner = jobRunner;
    }

    /** Nightly reconciliation catch: business body + JobRunner alert. */
    @SchedulerLock(name = JOB_NAME, lockAtMostFor = "PT30M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.linkage.reconcile-cron:0 45 3 * * *}")
    public void reconcileDaily() {
        try {
            LinkageReconcileSummary summary = reconcile();
            report(summary);
        } catch (Exception e) {
            log.warn("联动边账每日对账失败（只读扫描，下轮自动重试）: {}", e.getMessage());
            jobRunner.alert(JOB_NAME, e);
        }
    }

    /**
     * One reconciliation round: consume the orphan-edge scan once and fold
     * it into the findings summary. Read-only, safe to run on demand
     * (run-now picker and tests share this entry).
     */
    public LinkageReconcileSummary reconcile() {
        return LinkageReconcileSummary.from(orphanEdgeScanService.scan());
    }

    /**
 * Findings go to the log face only: zero findings is one quiet INFO,
 * every non-empty family is a WARN naming the count and where the fix
 * lives (the edge ledger), so the nightly audit trail survives without
 * inventing a second storage the escalation will not own.
 */
    private void report(LinkageReconcileSummary summary) {
        if (summary.findingsTotal() == 0) {
            log.info("联动边账每日对账：无发现（已武装成员 {} 个，覆盖外键 {} 项）",
                    summary.armedMembers().size(), summary.coveredForeignKeys());
            return;
        }
        if (!summary.orphans().isEmpty()) {
            log.warn("联动边账每日对账：{} 个外键列未登记联动边（修复位=变更联动边登记，逐列表见孤儿边扫描账）",
                    summary.orphans().size());
        }
        if (!summary.deadEdges().isEmpty()) {
            log.warn("联动边账每日对账：{} 条边的上游类型不在总线成员内，任何保存都不会触发它们（修复位=总线补类型或停用该边）",
                    summary.deadEdges().size());
        }
        if (!summary.busMembersWithoutEdges().isEmpty()) {
            log.warn("联动边账每日对账：{} 个总线成员尚无任何启用边（计划内欠账，见批次边扇出登记）",
                    summary.busMembersWithoutEdges().size());
        }
    }
}
