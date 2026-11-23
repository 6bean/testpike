package com.sixbean.testpike.core.imports.api.dto;


public record ImportRequirementRow(int rowNumber, String title, String chapter, String acceptanceCriteria,
                                   Integer source, String failReason) {
}
