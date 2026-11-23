package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoMatrixCombo(
        String engineCode,
        String platformCode,
        String deviceKindCode,
        Long envProfileId) {
}
