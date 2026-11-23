package com.sixbean.testpike.admin.cluster.infrastructure.remote.client;

import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollIssueBody;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenIssuedView;
import com.sixbean.testpike.admin.cluster.api.dto.FleetEnrollTokenView;

import java.util.List;

/**
 * Admin port to the core enrollment-token channel (/internal/cluster/enroll-tokens).
 * The whitelist decision (installkit.allow-roles) stays entirely on the core side;
 * this port only forwards the session-derived actor role alongside the body.
 */
public interface CoreClusterEnrollTokenClient {

    List<FleetEnrollTokenView> list();

    FleetEnrollTokenIssuedView issue(FleetEnrollIssueBody body, String actorRole);

    void revoke(Long id);
}
