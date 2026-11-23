package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoMatrixFanoutRequest(
        Long projectId,
        Long caseId,
        String name,
        Long envId,
        List<AppautoMatrixCombo> combos,
        Long reservationId,
        String deviceGroupLabel) {

    
    public AppautoMatrixFanoutRequest(Long projectId, Long caseId, String name,
                                      Long envId, List<AppautoMatrixCombo> combos) {
        this(projectId, caseId, name, envId, combos, null, null);
    }

    
    public AppautoMatrixFanoutRequest(Long projectId, Long caseId, String name,
                                      Long envId, List<AppautoMatrixCombo> combos,
                                      Long reservationId) {
        this(projectId, caseId, name, envId, combos, reservationId, null);
    }
}
