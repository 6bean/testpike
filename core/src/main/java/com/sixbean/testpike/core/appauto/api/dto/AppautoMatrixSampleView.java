package com.sixbean.testpike.core.appauto.api.dto;

import java.util.List;


public record AppautoMatrixSampleView(List<AppautoMatrixCombo> recommendations,
                                      String coverageMessage, String costMessage,
                                      String summary) {
}
