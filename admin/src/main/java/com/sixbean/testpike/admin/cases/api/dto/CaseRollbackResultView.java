package com.sixbean.testpike.admin.cases.api.dto;


public record CaseRollbackResultView(Long caseId, Integer rolledBackFrom, Integer newVersion,
                                     Integer version) {
}
