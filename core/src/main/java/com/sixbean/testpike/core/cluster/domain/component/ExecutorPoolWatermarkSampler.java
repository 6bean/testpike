package com.sixbean.testpike.core.cluster.domain.component;

import com.sixbean.testpike.core.cluster.api.dto.ExecutorPoolDemand;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolDemandService;
import com.sixbean.testpike.core.cluster.domain.service.ExecutorPoolWatermarkLedger;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Periodic pool-watermark sampler feeding the fleet water-level curve (T294d data plane).
 *
 * <p>Hook style = the same pattern as ClusterVersionScanScheduler (red line 6, no new
 * framework): &#64;Scheduled cron bound from a Spring property at startup
 * (testpike.cluster.watermark-sample-cron, shipped default every 5 minutes) +
 * &#64;SchedulerLock against double-runs on multi-instance installs + the
 * testpike.scheduling.enabled master gate. Per the RetentionCleanupScheduler split,
 * only this scheduling leaf carries the switch — the ledger business component stays
 * unconditional so test contexts that disable scheduling can still consume it.
 *
 * <p>Body = read the demand snapshot once, append it verbatim, then run the paired
 * retention purge: every sample row has a recycle path from its creation (R57). Failure
 * semantics = one warn line + JobRunner alert, self-healing on the next cycle; a missed
 * sample leaves a gap in the curve, which is honest history and never backfilled with a
 * guessed value.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ExecutorPoolWatermarkSampler {

    private static final Logger log = LoggerFactory.getLogger(ExecutorPoolWatermarkSampler.class);

    private final ExecutorPoolDemandService demandService;
    private final ExecutorPoolWatermarkLedger watermarkLedger;
    private final JobRunner jobRunner;

    public ExecutorPoolWatermarkSampler(ExecutorPoolDemandService demandService,
                                        ExecutorPoolWatermarkLedger watermarkLedger,
                                        JobRunner jobRunner) {
        this.demandService = demandService;
        this.watermarkLedger = watermarkLedger;
        this.jobRunner = jobRunner;
    }

    /** Snapshot every pool's water level into the history, then prune beyond retention. */
    @SchedulerLock(name = "clusterPoolWatermarkSample", lockAtMostFor = "PT10M")
    @Scheduled(cron = "${testpike.cluster.watermark-sample-cron:0 */5 * * * *}")
    public void samplePools() {
        try {
            List<ExecutorPoolDemand> demands = demandService.snapshotPools();
            watermarkLedger.appendAll(demands);
            int purged = watermarkLedger.purgeExpired();
            if (!demands.isEmpty() || purged > 0) {
                log.info("pool watermark sample: {} rows appended, {} rows pruned beyond retention",
                        demands.size(), purged);
            }
        } catch (Exception e) {
            log.warn("pool watermark sampling failed (retried next cycle): {}", e.getMessage());
            jobRunner.alert("clusterPoolWatermarkSample", e);
        }
    }
}
