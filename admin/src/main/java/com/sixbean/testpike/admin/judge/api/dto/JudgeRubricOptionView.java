package com.sixbean.testpike.admin.judge.api.dto;

/**
 * Rubric family head option for the run-creation picker (AW3/ segment A;
 * mirrors core RubricFamilyView field-for-field — the bridge forwards the
 * enumeration untouched, the latest-version decision is core's).
 *
 * @param id latest version row id (the value the run form submits)
 * @param name rubric family name
 * @param domain business-domain tag
 * @param version latest version number inside the family
 * @param passScore gate threshold of the latest version
 * @param status 1=gray 2=full 3=history
 * @param createdAt creation time of the latest row ('yyyy-MM-dd HH:mm:ss')
 */
public record JudgeRubricOptionView(Long id, String name, String domain, Integer version,
                                    Integer passScore, Integer status, String createdAt) {
}
