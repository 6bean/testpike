package com.sixbean.testpike.admin.sched.app.service.impl;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.sched.api.dto.SchedulerHealthView;
import com.sixbean.testpike.admin.sched.app.service.SchedulerAdminService;
import com.sixbean.testpike.admin.sched.infrastructure.remote.client.SchedulerAiClient;
import com.sixbean.testpike.admin.sched.infrastructure.remote.client.SchedulerCoreClient;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;


@Service
public class SchedulerAdminServiceImpl implements SchedulerAdminService {

    private final SchedulerCoreClient coreClient;
    private final SchedulerAiClient aiClient;

    public SchedulerAdminServiceImpl(SchedulerCoreClient coreClient, SchedulerAiClient aiClient) {
        this.coreClient = coreClient;
        this.aiClient = aiClient;
    }

    @Override
    public String run(String jobName, Long operatorId) {
        if (jobName == null || jobName.isBlank()) {
            throw new ServiceException("任务名不能为空");
        }
        String job = jobName.trim();
        if (aiClient.jobs().contains(job)) {
            return aiClient.runNow(job, operatorId);
        }
        return coreClient.runNow(job, operatorId);
    }

    @Override
    public List<String> jobs() {
        List<String> merged = new ArrayList<>(coreClient.jobs());
        merged.addAll(safeAiJobs());
        merged.sort(String::compareTo);
        return merged;
    }

    
    @Override
    public PageView<SchedulerHealthView> health(String keyword, int page, int size,
                                                String sortBy, String sortDir) {
        return coreClient.health(keyword, page, size, sortBy, sortDir);
    }

    
    private Set<String> safeAiJobs() {
        try {
            return aiClient.jobs();
        } catch (Exception e) {
            return Set.of();
        }
    }
}
