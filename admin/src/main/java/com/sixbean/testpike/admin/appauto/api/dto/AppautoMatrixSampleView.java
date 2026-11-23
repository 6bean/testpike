package com.sixbean.testpike.admin.appauto.api.dto;

import java.util.List;


public record AppautoMatrixSampleView(List<AppautoMatrixCombo> recommendations,
                                      String coverageMessage, String costMessage,
                                      String summary) {
}
