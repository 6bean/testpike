package com.sixbean.testpike.core.analysis.domain.component;

/**
 * Internal read shape: one active case together with the version whose steps
 * are counted (max of tp_case_version, falling back to the live version).
 *
 * @param caseId  case id
 * @param title   case title
 * @param version counted (latest) version
 */
public record CaseHead(long caseId, String title, int version) {
}
