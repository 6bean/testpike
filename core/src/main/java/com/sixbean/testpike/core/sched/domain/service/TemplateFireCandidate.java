package com.sixbean.testpike.core.sched.domain.service;

import java.time.Instant;


public record TemplateFireCandidate(long id, long projectId, Long createdBy,
                                    String cronExpr, Long scheduleEnvId) {
}
