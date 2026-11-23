package com.sixbean.testpike.core.appauto.api.dto;

import java.util.Map;


public record AppautoMatrixSampleProfile(int candidateCount, int installedModelAxes,
                                         Map<String, Double> engineRecentPassRate,
                                         int minutesPerTask, int fanoutMax) {
}
