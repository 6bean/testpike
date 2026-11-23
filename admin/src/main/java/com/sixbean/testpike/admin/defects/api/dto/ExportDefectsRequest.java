package com.sixbean.testpike.admin.defects.api.dto;

import java.util.List;


public record ExportDefectsRequest(long projectId, List<Long> ids, Integer status, Integer severity,
                                   Long assigneeId, Long caseId, String keyword) {
}
