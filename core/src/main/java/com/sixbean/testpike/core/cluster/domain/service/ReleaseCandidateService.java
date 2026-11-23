package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateDetectedInput;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseCandidateInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseManifestInfo;
import com.sixbean.testpike.core.cluster.api.dto.ReleaseRegisterInput;

import java.util.List;

/**
 * Release candidate service (AF batch two-state model: detection lands in the
 * candidate table, publishing stays a human-authorised act).
 * <p>The candidate table is the awareness surface; tp_release_manifest is the
 * authoritative publishing surface. The only promotion path is
 * {@link ReleaseManifestService#register} (validation single point reused — no
 * second implementation allowed); confirm writes back status=confirmed and stamps
 * the authoritative row id. Status spectrum detected -> confirmed/dismissed/
 * replaced is monotonic: re-detection never reopens a disposed row (no stale
 * banners, no re-notify after publishing).
 */
public interface ReleaseCandidateService {

    /**
     * Record a detection (shared write primitive for probing/import, idempotent
     * upsert keyed by version).
     * <p>Coverage semantics = payload/detected_at/channel/detected_source refresh
     * to the newest frame; status and confirmed_manifest_id never roll back.
     * If the authoritative table already carries the same version the row lands
     * as replaced directly (honest trail, no duplicate reminder); when a newer
     * detected version arrives, lower detected candidates become replaced (the
     * upstream feed only tracks the latest release).
     */
    ReleaseCandidateInfo recordDetected(ReleaseCandidateDetectedInput input);

    /** Pending candidates (status=detected, version-descending = banner order). */
    List<ReleaseCandidateInfo> listPending();

    /** Pending count (menu badge metric). */
    int pendingCount();

    /** Look up a candidate by version (null when absent; probe dedup / detail view). */
    ReleaseCandidateInfo findByVersion(String version);

    /**
     * Confirm = publish through the existing register validation single point.
     * Source is forced to import (machine-detected candidates belong to the
     * import class; the authoritative enum stays unextended — ruling #5). On
     * success the candidate turns confirmed, confirmed_manifest_id is recorded,
     * and lower detected candidates become replaced. Non-detected rows or a
     * version mismatch are rejected in plain words.
     */
    ReleaseManifestInfo confirm(Long candidateId, ReleaseRegisterInput input);

    /**
     * Dismiss a candidate (keeps banners from lingering; the reason is kept in
     * the payload trail). Only detected rows can be dismissed; dismissing twice
     * is rejected with an honest "already dismissed" message.
     */
    ReleaseCandidateInfo dismiss(Long candidateId, String reason);
}
