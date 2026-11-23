package com.sixbean.testpike.core.project.api.dto;


public record EnvDeployBatchRef(long batchId, String batchNo, String batchName,
                                String envVersionSnapshot, boolean matched, String finishedAt) {
}
