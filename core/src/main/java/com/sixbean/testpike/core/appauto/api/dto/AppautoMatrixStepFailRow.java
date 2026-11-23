package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoMatrixStepFailRow(int stepNo, String action, int failCount,
                                       String sampleDetail) {
}
