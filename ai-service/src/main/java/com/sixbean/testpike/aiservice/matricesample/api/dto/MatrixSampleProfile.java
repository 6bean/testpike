package com.sixbean.testpike.aiservice.matricesample.api.dto;

import java.util.Map;


public record MatrixSampleProfile(
        int candidateCount,
        int installedModelAxes,
        Map<String, Double> engineRecentPassRate,
        int minutesPerTask,
        int fanoutMax) {
}
