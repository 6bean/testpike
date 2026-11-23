package com.sixbean.testpike.aiservice.matricesample.api.dto;

import java.util.List;


public record MatrixSampleRequest(
        Long projectId,
        Long caseId,
        List<MatrixSampleCandidate> candidates,
        MatrixSampleProfile profile,
        int topN) {
}
