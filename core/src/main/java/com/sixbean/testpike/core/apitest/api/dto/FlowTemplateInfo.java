package com.sixbean.testpike.core.apitest.api.dto;

/**
 * Flow template view .
 *
 * @param sourceCollectionId provenance only — null once the source collection
 * is deleted (the frozen snapshot stands on its own)
 * @param stepsSnapshotJson pinned-shape request array (same keys as the
 * collection-run ledger's pinned_json)
 */
public record FlowTemplateInfo(long id, long projectId, String name, String description,
                               Long sourceCollectionId, String stepsSnapshotJson) {
}
