package com.sixbean.testpike.admin.requirement.api.dto;

import java.util.List;


public record TraceNodeView(String type, long id, long projectId, String label,
                            Integer status, String statusText, String routePath,
                            Long lastBatchId, String lastBatchName,
                            Integer lastExecStatus, String lastExecTime, String source) {
}
