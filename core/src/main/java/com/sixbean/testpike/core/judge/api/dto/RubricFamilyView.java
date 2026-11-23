package com.sixbean.testpike.core.judge.api.dto;

/**
 * Rubric family head view for the run-creation option list (AW3/ segment A;
 * tp_judge_rubric latest-version row per (name, domain) family — only the
 * fields a picker needs, the heavy dimensions/rules payload stays behind the
 * detail door. Read-only projection: no judgement, no mutation).
 *
 * @param id latest version row id (JudgeRun creation pins this row)
 * @param name rubric family name
 * @param domain business-domain tag
 * @param version latest version number inside the family
 * @param passScore gate threshold of the latest version
 * @param status 1=gray 2=full 3=history (latest row's status)
 * @param createdAt creation time of the latest row ('yyyy-MM-dd HH:mm:ss')
 */
public record RubricFamilyView(Long id, String name, String domain, Integer version,
                               Integer passScore, Integer status, String createdAt) {
}
