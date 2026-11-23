package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoMatrixStepFailRow(
        int stepNo,
        String action,
        int failCount,
        String sampleDetail) {
}
