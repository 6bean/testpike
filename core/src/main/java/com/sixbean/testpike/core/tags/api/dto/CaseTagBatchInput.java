package com.sixbean.testpike.core.tags.api.dto;

import java.util.List;


public record CaseTagBatchInput(long projectId, List<Long> caseIds) {
}
