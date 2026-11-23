package com.sixbean.testpike.core.behavior.api.dto;

import java.math.BigDecimal;

/**
 * Per-template-version rollup of the outcome ledger: how many verdicts landed
 * on a version and the weighted result rate they imply (adopted = full credit,
 * corrected/partial = half credit, deprecated = none).
 */
public record BehaviorOutcomeStat(Long templateVersionId, long eventCount, BigDecimal resultRate) {
}
