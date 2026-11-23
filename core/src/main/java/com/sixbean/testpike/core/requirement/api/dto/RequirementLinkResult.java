package com.sixbean.testpike.core.requirement.api.dto;

import java.util.List;


public record RequirementLinkResult(Long requirementId, List<Long> caseIds, int linkedCount) {
}
