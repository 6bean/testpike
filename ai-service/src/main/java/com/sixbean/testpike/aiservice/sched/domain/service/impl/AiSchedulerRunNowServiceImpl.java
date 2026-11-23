package com.sixbean.testpike.aiservice.sched.domain.service.impl;

import com.sixbean.testpike.aiservice.aictx.domain.component.CtxDistillScheduler;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeReconcileScheduler;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import com.sixbean.testpike.aiservice.llm.domain.component.UsageCalibrationRefresher;
import com.sixbean.testpike.aiservice.sched.domain.service.AiSchedulerRunNowService;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;


@Service
@ConditionalOnProperty(name = "testpike.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class AiSchedulerRunNowServiceImpl implements AiSchedulerRunNowService {

    private final Map<String, Runnable> registry;
    private final CoreGateway coreGateway;

    public AiSchedulerRunNowServiceImpl(KnowledgeReconcileScheduler reconcileScheduler,
                                        UsageCalibrationRefresher usageRefresher,
                                        CtxDistillScheduler distillScheduler,
                                        com.sixbean.testpike.aiservice.llm.domain.component.LlmCapabilityReprobeScheduler capabilityReprobeScheduler,
                                        CoreGateway coreGateway) {
        this.coreGateway = coreGateway;
        this.registry = new LinkedHashMap<>();
        registry.put("knowledgeReconcileDaily", reconcileScheduler::reconcileDaily);
        registry.put("usageCalibrationRefresh", usageRefresher::refresh);
        registry.put("aiCtxDistillDaily", distillScheduler::distillDaily);
        registry.put("llmCapabilityReprobe", capabilityReprobeScheduler::reprobeHourly);
    }

    @Override
    public void run(String jobName, Long operatorId) {
        if (jobName == null || jobName.isBlank()) {
            throw new ServiceException("任务名不能为空");
        }
        Runnable job = registry.get(jobName.trim());
        if (job == null) {
            throw new ServiceException("未知的定时任务：" + jobName.trim()
                    + "（ai-service 可选任务：" + String.join("、", registry.keySet()) + "）");
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
                ? Map.of("mode", "manual-run-now", "module", "ai-service")
                : Map.of("mode", "manual-run-now", "module", "ai-service", "reason", reason);
        coreGateway.writeAudit(new LlmAuditRequest(operatorId, null, "scheduler", jobName,
                action, null, after));
    }
}
