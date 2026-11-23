package com.sixbean.testpike.core.appauto.domain.service.impl;

import com.sixbean.testpike.core.appauto.api.dto.AppautoMatrixCombo;

import java.util.List;
import java.util.Map;


public record AppautoMatrixPlan(AppautoMatrixCaseRow caseRow,
                                List<Map<String, Object>> appSteps,
                                List<AppautoMatrixCombo> combos) {
}
