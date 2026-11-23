package com.sixbean.testpike.core.defects.api.dto;

import java.util.List;


public record DefectExportRequest(long projectId, List<Long> ids, Integer status, Integer severity,
                                  Long assigneeId, Long caseId, String keyword) {
}
