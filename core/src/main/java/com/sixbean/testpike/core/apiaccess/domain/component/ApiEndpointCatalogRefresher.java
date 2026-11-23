package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiEndpointSpec;
import com.sixbean.testpike.core.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Keeps the pass-through catalog aligned with the live request mappings.
 *
 * <p>Why this exists: the catalog is generated, so a new endpoint needs no registration — but a
 * long-running process only notices new mappings when somebody asks. The scheduled rebuild closes
 * that gap without a restart (late-registered handlers, plugin-supplied controllers, or an
 * operator pressing refresh after a hot deploy), and it logs only when the width actually changes
 * so the sweep stays quiet.
 *
 * <p>Multi-instance safe: ShedLock keeps the rebuild to one node per window, and the catalog
 * snapshot is a volatile replace (a reader either sees the old or the new complete map, never a
 * half-built one).
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class ApiEndpointCatalogRefresher {

    private static final Logger log = LoggerFactory.getLogger(ApiEndpointCatalogRefresher.class);

    private final ApiEndpointCatalog catalog;
    private final JobRunner jobRunner;

    public ApiEndpointCatalogRefresher(ApiEndpointCatalog catalog, JobRunner jobRunner) {
        this.catalog = catalog;
        this.jobRunner = jobRunner;
    }

    /**
     * Rebuild on the configured cron (property {@code testpike.api-access.catalog-refresh-cron}).
     *
     * <p>Same posture as every other scheduler in this service: the work is idempotent, a failed
     * round simply means the next one retries, and the failure is raised through the alert chain
     * rather than dying in the scheduler thread — the coverage tests in the sched domain police
     * exactly that (a {@code @Scheduled} method without a registered job name and alert wiring is
     * red, and it caught this omission).
     */
    @SchedulerLock(name = "apiAccessCatalogRefresh", lockAtMostFor = "PT5M", lockAtLeastFor = "PT5S")
    @Scheduled(cron = "${testpike.api-access.catalog-refresh-cron:0 */5 * * * *}")
    public void refreshCatalog() {
        try {
            int before = catalog.endpoints().size();
            Map<String, ApiEndpointSpec> after = catalog.refresh();
            if (after.size() != before) {
                log.info("api pass-through catalog width changed from {} to {}", before, after.size());
            }
        } catch (Exception ex) {
            log.warn("catalog rebuild failed (next round retries, the door keeps serving the last"
                    + " complete snapshot): {}", ex.getMessage());
            jobRunner.alert("apiAccessCatalogRefresh", ex);
        }
    }
}
