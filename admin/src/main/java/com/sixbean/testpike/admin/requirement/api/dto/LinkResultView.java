package com.sixbean.testpike.admin.requirement.api.dto;

import java.util.List;


public record LinkResultView(Long requirementId, List<Long> caseIds, int linkedCount) {
}
