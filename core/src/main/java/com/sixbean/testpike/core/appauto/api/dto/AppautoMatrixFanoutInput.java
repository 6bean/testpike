package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoMatrixFanoutInput(long projectId, long caseId, String name,
                                       Long envId, List<AppautoMatrixCombo> combos,
                                       Long reservationId, String deviceGroupLabel) {

    
    public AppautoMatrixFanoutInput(long projectId, long caseId, String name,
                                    Long envId, List<AppautoMatrixCombo> combos) {
        this(projectId, caseId, name, envId, combos, null, null);
    }

    
    public AppautoMatrixFanoutInput(long projectId, long caseId, String name,
                                    Long envId, List<AppautoMatrixCombo> combos,
                                    Long reservationId) {
        this(projectId, caseId, name, envId, combos, reservationId, null);
    }
}
