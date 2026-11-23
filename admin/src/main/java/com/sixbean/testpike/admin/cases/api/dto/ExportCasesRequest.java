package com.sixbean.testpike.admin.cases.api.dto;

import java.util.List;


public record ExportCasesRequest(long projectId, List<Long> ids, Integer status, String keyword,
                                 Integer priority, Integer confidence, Long assigneeId,
                                 Integer phase, List<Long> tagIds) {
}
