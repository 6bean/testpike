package com.sixbean.testpike.admin.sample.api.dto;


public record TrainingSampleView(Long id, Long caseId, String caseNo, Long projectId,
                                 String sampleType, String sampleJson, Integer sourceVersion,
                                 String createdAt, Integer adopted) {
}
