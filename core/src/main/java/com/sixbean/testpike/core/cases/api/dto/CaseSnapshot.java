package com.sixbean.testpike.core.cases.api.dto;

import java.util.List;

/**
 * Test-case version snapshot (tp_case_version.snapshot_json structure; 01 'complete test-case content snapshot (including steps, expected results, and script references)').
 * <p>Scripts are included with the version snapshot (B5 semantics, PRD 9.3.5); assigneeId is system data (operator attribution), and diff is hidden by default.
 * <p>The order of record components is the JSON field order (CaseSnapshotCodec binds bidirectionally; corruption means deserialization failure or structure-validation failure).
 *
 */
public record CaseSnapshot(String title, String precondition, String expectedResult,
                           Integer priority, Integer confidence, String endType, Integer phase,
                           Integer status, Long assigneeId, List<CaseSnapshotStep> steps,
                           String scriptSchemaVersion, String script) {
}
