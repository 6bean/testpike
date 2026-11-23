package com.sixbean.testpike.core.sample.api.dto;


public record TrainingSampleItem(Long id, Long caseId, String caseNo, Long projectId,
                                 String sampleType, String sampleJson, Integer sourceVersion,
                                 String createdAt, Integer adopted) {
}
