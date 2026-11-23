package com.sixbean.testpike.core.sched.domain.service;

import java.time.Instant;


public record SuiteFireCandidate(long id, long projectId, long createdBy,
                                 String cronExpr, Instant lastRunAt) {
}
