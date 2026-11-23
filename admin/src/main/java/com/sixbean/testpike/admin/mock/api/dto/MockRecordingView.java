package com.sixbean.testpike.admin.mock.api.dto;

import java.time.LocalDateTime;

/**
 * Cassette-box view (admin bridge; field-for-field mirror of the core
 * MockRecordingInfo record). Nulls stay null: an open box has no ended_at and
 * fields (summary/cassette) are honestly empty until capture lands.
 *
 * @param id row id
 * @param spaceId owning space
 * @param targetBaseUrl allowlisted upstream the box records against
 * @param status recording | stopped | promoted | failed
 * @param summaryJson per-endpoint cluster stats as json text
 * @param cassetteRef S3 artifact reference of the captured body
 * @param promotedCount stubs promoted from this box
 * @param startedAt box-open stamp
 * @param endedAt box-seal stamp (null while open)
 * @param createdAt row creation time
 * @param updatedAt last mutation time
 */
public record MockRecordingView(
        Long id,
        Long spaceId,
        String targetBaseUrl,
        String status,
        String summaryJson,
        String cassetteRef,
        Integer promotedCount,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {
}
