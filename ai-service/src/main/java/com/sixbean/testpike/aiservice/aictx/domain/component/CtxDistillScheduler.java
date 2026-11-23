package com.sixbean.testpike.aiservice.aictx.domain.component;

import com.sixbean.testpike.aiservice.aictx.domain.service.CtxDistillService;
import com.sixbean.testpike.aiservice.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Daily correction-distillation job (AP batch , mirrors
 * KnowledgeReconcileScheduler). Delegates to {@link CtxDistillService#distillOnce},
 * which is idempotent per watermark window; the catch-and-alert shape keeps
 * scheduling continuity first (failures retry on the next pass).
 * <p>cron key {@code testpike.ai-ctx.distill-cron} ships 04:30 — knowledge
 * reconcile holds 02:00, retention 03:30 (14 §6.3 window budget).
 * <p>Explicit drive face: registered as {@code aiCtxDistillDaily} in the
 * ai-service run-now catalogue (SchedulerRunNowInternalController), the
 * same channel L4/E2E use for knowledgeReconcileDaily.
 */
@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class CtxDistillScheduler {

    private static final Logger log = LoggerFactory.getLogger(CtxDistillScheduler.class);

    private final CtxDistillService distillService;
    private final JobRunner jobRunner;

    public CtxDistillScheduler(CtxDistillService distillService, JobRunner jobRunner) {
        this.distillService = distillService;
        this.jobRunner = jobRunner;
    }

    /** Daily 04:30 incremental distillation over the four feedback sources. */
    @SchedulerLock(name = "aiCtxDistillDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.ai-ctx.distill-cron:0 30 4 * * *}")
    public void distillDaily() {
        try {
            int created = distillService.distillOnce();
            log.info("correction distillation pass finished, new project-memory entries={}", created);
        } catch (Exception e) {
            log.warn("correction distillation failed (watermarks keep the window for the next pass): {}",
                    e.getMessage());
            jobRunner.alert("aiCtxDistillDaily", e);
        }
    }
}
