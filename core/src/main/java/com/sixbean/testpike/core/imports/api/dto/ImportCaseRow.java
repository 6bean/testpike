package com.sixbean.testpike.core.imports.api.dto;

import com.sixbean.testpike.core.cases.api.dto.CaseStepInput;

import java.util.List;


public record ImportCaseRow(int rowNumber, String title, String precondition, String expectedResult,
                            Integer priority, Integer confidence, String endType,
                            List<CaseStepInput> steps, String failReason) {
}
