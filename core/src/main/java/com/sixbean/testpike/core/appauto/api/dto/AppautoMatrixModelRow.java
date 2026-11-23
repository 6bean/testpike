package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoMatrixModelRow(String model, String osVersion, int samples,
                                    int passSamples, int failSamples, double passRate,
                                    List<AppautoMatrixStepFailRow> stepFailures,
                                    String message) {
}
