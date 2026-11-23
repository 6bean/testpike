package com.sixbean.testpike.core.appauto.domain.service.impl;

import java.util.List;
import java.util.Map;


public record AppautoMatrixCaseRow(long id, String title, int version, String automationTarget,
                                   String appautoEngine, Long appautoEnvProfileId,
                                   int status, int flaky) {
}
