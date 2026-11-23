package com.sixbean.testpike.admin.cases.api.dto;

import java.time.LocalDateTime;


public record CaseVersionView(Long id, Long caseId, Integer version, Integer changeType,
                              Long operatorId, String operatorName, LocalDateTime createdAt,
                              Integer changedFieldCount, boolean corrupted) {
}
