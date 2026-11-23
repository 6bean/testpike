package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.ReleaseCandidateView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseManifestView;
import com.sixbean.testpike.admin.cluster.api.dto.ReleaseRegisterRequest;

import java.util.List;

/**
 * core release-candidate client interface (same posture as
 * CoreReleaseManifestClient — thin pass-through, zero reprocessing).
 */
public interface CoreReleaseCandidateClient {

    /** Pending candidates (status=detected, version-descending = card order). */
    List<ReleaseCandidateView> listPending();

    /** Pending count (menu badge metric). */
    int pendingCount();

    /** Confirm a candidate as a release (register validation single point in core). */
    ReleaseManifestView confirm(long candidateId, ReleaseRegisterRequest request);

    /** Dismiss a candidate (reason required, validated in core). */
    ReleaseCandidateView dismiss(long candidateId, String reason);
}
