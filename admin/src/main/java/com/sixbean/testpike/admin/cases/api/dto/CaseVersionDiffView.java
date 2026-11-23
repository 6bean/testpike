package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record CaseVersionDiffView(Long caseId, Integer fromVersion, Integer toVersion,
                                  boolean truncated, List<CaseVersionChangeView> changes) {
}
