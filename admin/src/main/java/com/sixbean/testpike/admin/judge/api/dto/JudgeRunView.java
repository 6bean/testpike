package com.sixbean.testpike.admin.judge.api.dto;

import java.util.Map;

/**
 * Evaluation run row as shown by the admin facade (AW4; mirrors core
 * RunView field-for-field, forwarded without any second judgement).
 *
 * @param id             run id
 * @param rubricName     pinned rubric family name
 * @param rubricDomain   pinned rubric business domain
 * @param rubricVersion  pinned rubric version number
 * @param rubricSnapshot full frozen rubric snapshot (dimensions/rules/thresholds/weights)
 * @param evalsetName    evaluation set name
 * @param evalsetVersion evaluation set version
 * @param evalsetDigest  frozen version digest anchor
 * @param targetType     pipeline / external
 * @param targetRef      evaluated target reference
 * @param llmConfigId    judge model reference (nullable = pure-rule scorecard)
 * @param status         0=pending 1=running 2=done 3=failed
 * @param createdBy      creator user id
 * @param createdAt      creation time ('yyyy-MM-dd HH:mm:ss')
 * @param finishedAt     terminal time (null until finished)
 */
public record JudgeRunView(Long id, String rubricName, String rubricDomain, Integer rubricVersion,
                           Map<String, Object> rubricSnapshot, String evalsetName,
                           Integer evalsetVersion, String evalsetDigest, String targetType,
                           String targetRef, Long llmConfigId, Integer status, Long createdBy,
                           String createdAt, String finishedAt) {
}
