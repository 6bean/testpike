package com.sixbean.testpike.core.requirement.api.dto;

/**
 *
 * @param expectedVersion optimistic-lock baseline (nullable = no client anchor; the
 * row version read just now is used for the CAS guard)
 */
public record RequirementUpdateInput(String title, Integer source, String sourceId,
                                     String acceptanceCriteria, String chapter, Integer status,
                                     Integer expectedVersion) {
}
