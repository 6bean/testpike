package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.EnrollAcceptedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollHeartbeatView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssueInput;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenIssuedView;
import com.sixbean.testpike.core.cluster.api.dto.EnrollTokenView;

import java.util.List;

/**
 * The install-enrollment one-time token service is documented in a frozen AB-wave document.
 * <p>The semantic chain is: the page Add Executor/AI Node leads to issuance, where the plaintext appears only once in the response.
 * Generate a copyable command with the token, and redeem it on a new machine via installkit enroll, binding the machine fingerprint on first use.
 * When maxUses is exhausted, mark the token as redeemed, fail closed on a different machine, and emit heartbeat install progress metrics.
 * The platform never holds SSH credentials, and scripts are always executed by the user on their own machine.
 */
public interface ClusterEnrollTokenService {

    /** Issue a one-time enrollment token with the TTL read from the K8 key installkit.token-ttl-seconds, falling back to a conservative default if the value is invalid. */
    EnrollTokenIssuedView issue(EnrollTokenIssueInput input);

    /** Redeem the token through the enroll exchange, return the role and project scope, and reject expired, revoked, exhausted, or mismatched-fingerprint requests with human-readable messages. */
    EnrollAcceptedView enroll(String token, String fingerprint);

    /** Report install or heartbeat progress metrics only when the token is unbound or the fingerprint matches, to prevent forged progress. */
    void heartbeat(String token, String fingerprint);

    /**
     * Heartbeat + step projection (03 trailing step keys): step values
     * download/deps/register/healthcheck (the four-cell progress bar on the node
     * card, single source), stepStatus running/done/failed defaulting to done,
     * message plain-word note truncated at 500; step=null = old-form accounting
     * only (behavior unchanged, zero residue).
     */
    EnrollHeartbeatView heartbeat(String token, String fingerprint, String step,
                                  String stepStatus, String message);

    /**
 * Heartbeat + trailing components ledger : the
 * host service-agent reports this machine's platform-family current versions
 * through the same token channel. Rows land on the ONE component ledger via
 * ClusterComponentIngestor (same channel and same judgement spectrum as
 * executor heartbeats — never a second ledger), addressed "token:&lt;id&gt;"
 * so the upgrade wave can resolve the host back through the token row.
 * Missing/empty components = zero ingest calls (old reporters, zero drift);
 * ingest failure never breaks the heartbeat main chain.
 */
    EnrollHeartbeatView heartbeat(String token, String fingerprint, String step,
                                  String stepStatus, String message, List<?> components);

    /** Revoke a pending token, reject revocation of a redeemed token, and return a human-readable 404 when the token does not exist. */
    void revoke(Long id);

    /** List tokens on the management plane with a view that exposes neither plaintext nor digests. */
    List<EnrollTokenView> list();

    /**
 * The protocol-plane parser resolves a token digest to a token row id, and in the poll-enroll form the API layer first folds the plaintext into a digest.
 * The SHA-256 digest is then passed in, the core does not accept plaintext, and a not-found lookup returns null because an invalid token has no obtainable command.
     * Do not allow the observability plane to crash.
     */
    Long resolveTokenIdByDigest(String tokenDigest);

    /** The default installation mirror source is read from the K8 key installkit.mirror-base-url, with missing or blank values treated as an empty string meaning the official source. */
    String mirrorBaseUrl();
}
