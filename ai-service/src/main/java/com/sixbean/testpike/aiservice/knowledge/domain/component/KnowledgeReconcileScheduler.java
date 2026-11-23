package com.sixbean.testpike.aiservice.knowledge.domain.component;

import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeReconcileService;
import com.sixbean.testpike.aiservice.sched.domain.component.JobRunner;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;


@Component
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class KnowledgeReconcileScheduler {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReconcileScheduler.class);

    private final KnowledgeReconcileService reconcileService;
    private final com.sixbean.testpike.aiservice.sched.domain.component.JobRunner jobRunner;

    public KnowledgeReconcileScheduler(KnowledgeReconcileService reconcileService,
                                       com.sixbean.testpike.aiservice.sched.domain.component.JobRunner jobRunner) {
        this.reconcileService = reconcileService;
        this.jobRunner = jobRunner;
    }

    
    @SchedulerLock(name = "knowledgeReconcileDaily", lockAtMostFor = "PT2H")
    @Scheduled(cron = "${testpike.knowledge.reconcile-cron:0 0 2 * * *}")
    public void reconcileDaily() {
        try {
            reconcileService.reconcile();
        } catch (Exception e) {
            log.warn("G1 知识库对账失败（下轮自动重试，幂等收敛）: {}", e.getMessage());
            jobRunner.alert("knowledgeReconcileDaily", e);
        }
    }
}
