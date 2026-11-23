package com.sixbean.testpike.aiservice.explore.domain.component;

/**
 *
 * @param candidatePoolJson raw interactive-candidate pool detail carried by an
 * element-candidates step (WE-S3; null for every
 * other action — presence alone gates core-side
 * anchor reconciliation)
 */
record ExploreActOutcome(String stepResultJson, String screenshotKey,
                         String candidatePoolJson) {
}
