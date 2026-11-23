package com.sixbean.testpike.admin.project.api.dto;


public record DeployBatchRefView(long batchId, String batchNo, String batchName,
                                 String envVersionSnapshot, boolean matched, String finishedAt) {
}
