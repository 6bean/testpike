package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoMatrixSampleLlmRequest(
        Long projectId,
        Long caseId,
        List<AppautoMatrixSampleCandidate> candidates,
        AppautoMatrixSampleProfile profile,
        int topN) {
}
