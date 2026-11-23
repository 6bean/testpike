package com.sixbean.testpike.core.requirement.api.dto;


public record TraceNode(TraceNodeType type, long id, long projectId, String label,
                        Integer status, String statusText, String routePath,
                        Long lastBatchId, String lastBatchName,
                        Integer lastExecStatus, String lastExecTime, String source) {
}
