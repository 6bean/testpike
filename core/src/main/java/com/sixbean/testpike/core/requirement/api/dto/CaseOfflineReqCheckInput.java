package com.sixbean.testpike.core.requirement.api.dto;

import java.util.List;


public record CaseOfflineReqCheckInput(long projectId, List<Long> caseIds) {
}
