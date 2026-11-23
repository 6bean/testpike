package com.sixbean.testpike.core.mock.api.dto;

import java.time.LocalDateTime;

/**
 * One cassette box of the recording control plane .
 * <p>A box tracks live proxy traffic for one published space against one
 * allowlisted target; fills summary_json/cassette_ref while
 * owns the open/seal lifecycle stamps.
 *
 * @param id row id
 * @param spaceId owning space (recording is always scoped to one space)
 * @param targetBaseUrl allowlisted upstream the box records against
 * @param status recording | stopped | promoted | failed
 * @param summaryJson per-endpoint cluster stats as json text fills)
 * @param cassetteRef S3 artifact reference of the captured body
 * @param promotedCount how many stubs were promoted from this box
 * @param startedAt box-open stamp written by the control plane
 * @param endedAt box-seal stamp (null while open)
 * @param createdAt row creation time
 * @param updatedAt last mutation time
 */
public record MockRecordingInfo(
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
