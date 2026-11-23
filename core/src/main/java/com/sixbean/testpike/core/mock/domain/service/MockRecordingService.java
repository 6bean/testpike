package com.sixbean.testpike.core.mock.domain.service;

import com.sixbean.testpike.core.mock.api.dto.MockRecordingInfo;
import com.sixbean.testpike.core.mock.api.dto.MockRecordingPageView;

import java.util.List;

/**
 * Recording control-plane atomic service open/seal/list
 * cassette boxes). segment 2 (proxy capture) consumes the box rows and the
 * space's bound proxy target; this segment owns the gates and stamps only.
 */
public interface MockRecordingService {

    /**
     * Open a cassette box for a published space against an allowlisted target.
     * Refusals in plain words: unknown space; space not published (no served
     * traffic to record); blank or non-http(s) target; empty allowlist (the
     * proxy is off until an operator opens hosts — fail-closed); target host
     * outside the allowlist; a box already open for this space. On success the
     * box row carries started_at and the space row binds proxy_target_url for
     * the forwarder.
     */
    long start(long spaceId, String targetBaseUrl);

    /**
     * Seal an open box (recording → stopped, ended_at stamped). A ghost id or
     * an already-sealed box is refused — reopening means a new box, never
     * resurrecting history. Returns the refreshed row so callers re-read never.
     */
    MockRecordingInfo stop(long recordingId);

    /** Boxes of one space, newest first (unknown space refused, not empty-listed). */
    List<MockRecordingInfo> listBySpace(long spaceId);

    /**
 * One paged window of the space's boxes . Null/blank sort
 * keeps the historical id DESC order; a whitelisted key orders in SQL with
 * the id DESC tie-break. Off-list or injection-shaped keys are refused
 * fail-closed by name; a ghost space is refused, not empty-listed.
 */
    MockRecordingPageView listBySpacePaged(
            long spaceId, int page, int pageSize, String sortBy, String sortDir);
}
