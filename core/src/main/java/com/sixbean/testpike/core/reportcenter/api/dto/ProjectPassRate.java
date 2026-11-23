package com.sixbean.testpike.core.reportcenter.api.dto;

import java.math.BigDecimal;

/**
 * Pass-rate reading for one project inside a program overview.
 *
 * @param projectId project the value belongs to (lens projects ∩ caller visibility)
 * @param passRate  caliber "pass-rate" scalar result; null = no executions in period
 */
public record ProjectPassRate(long projectId, BigDecimal passRate) {
}
