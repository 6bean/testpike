package com.sixbean.testpike.core.mcp.domain.component;

import com.sixbean.testpike.core.mcp.api.dto.McpKnowledgeSyncScanResult;
import com.sixbean.testpike.core.mcp.domain.service.McpKnowledgeSyncService;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * MCP knowledge sync due scanner .
 *
 * <p>Same posture as the established @Scheduled pattern (SpecSubscriptionScheduler
 * precedent): per-minute cron through the {@code testpike.mcp.sync-cron} key,
 * the real due decision lives in the SQL-side scan (per-subscription
 * sync_interval_minutes), this component only fires the scan. Exceptions are
 * alerted through JobRunner and retried by the next round (each subscription
 * round is independent and idempotent by payload fingerprint).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class McpKnowledgeSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(McpKnowledgeSyncScheduler.class);

    private final McpKnowledgeSyncService syncService;
    private final JobRunner jobRunner;

    public McpKnowledgeSyncScheduler(McpKnowledgeSyncService syncService, JobRunner jobRunner) {
        this.syncService = syncService;
        this.jobRunner = jobRunner;
    }

    /** Every minute: pull due subscriptions (sec min hour day month week). */
    @SchedulerLock(name = "mcpKnowledgeSyncScan", lockAtMostFor = "PT55S")
    @Scheduled(cron = "${testpike.mcp.sync-cron:0 * * * * *}")
    public void scanMinute() {
        try {
            McpKnowledgeSyncScanResult result = syncService.scanDue();
            if (result.scanned() > 0) {
                log.info("MCP knowledge sync scan: {} due ({} updated / {} unchanged / {} failed)",
                        result.scanned(), result.updated(), result.unchanged(), result.failed());
            }
        } catch (Exception e) {
            log.warn("MCP knowledge sync scan failed (next round retries, per-subscription idempotent): {}",
                    e.getMessage());
            jobRunner.alert("mcpKnowledgeSyncScan", e);
        }
    }
}
