package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Admin mirror of core FlowTemplateInfo (frozen flow snapshot row).
 *
 * @param sourceCollectionId provenance only — null once the source collection
 *                           is deleted (the snapshot stands on its own)
 * @param stepsSnapshotJson  pinned-shape request array, ::text round-tripped
 */
public record FlowTemplateView(long id, long projectId, String name, String description,
                               Long sourceCollectionId, String stepsSnapshotJson) {
}
