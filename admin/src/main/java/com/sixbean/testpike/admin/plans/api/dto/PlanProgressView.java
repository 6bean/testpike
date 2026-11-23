package com.sixbean.testpike.admin.plans.api.dto;

import java.util.List;


public record PlanProgressView(int total, int notRun, int running, int passed, int failed, int skipped,
                               double passRate, List<DimRow> byAssignee, List<DimRow> byPhase,
                               List<DimRow> byEnv) {

    
    public record DimRow(String key, String label, int total, int passed, int failed,
                         int notRun, int running, int skipped) {
    }
}
