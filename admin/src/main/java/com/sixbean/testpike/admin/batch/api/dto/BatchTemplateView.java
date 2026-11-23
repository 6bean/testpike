package com.sixbean.testpike.admin.batch.api.dto;

import java.util.List;


public record BatchTemplateView(long id, long projectId, String name, List<Long> caseIds,
                                String envPolicy, Long createdBy, String createdAt,
                                String cronExpr, int enabled, Long scheduleEnvId,
                                int cleanAppData) {

    
    public BatchTemplateView(long id, long projectId, String name, List<Long> caseIds,
                             String envPolicy, Long createdBy, String createdAt,
                             String cronExpr, int enabled, Long scheduleEnvId) {
        this(id, projectId, name, caseIds, envPolicy, createdBy, createdAt, cronExpr, enabled,
                scheduleEnvId, 0);
    }
}
