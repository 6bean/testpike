package com.sixbean.testpike.core.cluster.domain.service;

import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandInput;
import com.sixbean.testpike.core.cluster.api.dto.ClusterCommandView;

import java.util.List;

/**
 * This is the cluster command queue service (AB wave, single source of truth for the protocol 03 contract).
 * <p> The semantic chain is: selecting and dispatching from the cluster page/engine center enqueues commands (scale-in is linked to drain_requested in the same transaction).
 * The executor/installkit pull model uses pollClaimed to claim commands (CAS 0→1), then progress advances (1→2, refreshing the node card in real time).
 * complete returns the final receipt state (2→3/4; a successful scale-in receipt releases drain); the four command types are engine-install, engine-uninstall, scale-in, and node-remove.
 * engine-uninstall, scale-in, and node-remove share the same CHECK set in migration artifacts; adding types must synchronize protocol 03 and forbid dual writes.
 * Zero credentials: plaintext tokens never enter command bodies (red line 4); enroll-token targets are allowed only when the bound fingerprint matches the executor registration name.
 */
public interface ClusterCommandService {

    /** Enqueue/dispatch validates the value domain, target existence, and engine catalog item by item; invalid requests are rejected with a human-readable message to prevent wasting executor sessions. */
    ClusterCommandView enqueue(ClusterCommandInput input);

    /** Pull and claim: returns non-expired pending-dispatch executor-type commands available to this executor and uses CAS 0→1, so concurrent double claims yield only one winner. */
    List<ClusterCommandView> pollClaimed(long executorId, int limit);

    /** Pull and claim: returns commands available to a machine awaiting enrollment (enroll-token type), allowing access only when the token row's bound fingerprint matches the caller's fingerprint. */
    List<ClusterCommandView> pollClaimedForEnroll(long enrollTokenId, String fingerprint, int limit);

    /** Progress advancement performs an idempotent refresh from 1 to 2: step/message overwrites last_progress, which is the real-time data source for the node card. */
    void progress(long commandId, long claimerId, String step, String message);

    /**
     * Progress advance carrying the enrollment-side receipt identity (03 §2.9
     * receipts half of the bidirectional authentication contract): commands
     * targeting an enroll-token machine must present the token digest and the
     * bound fingerprint and reconcile against claimed_by before any write;
     * executor targets ignore both keys (documented intranet-trust shape).
     */
    void progress(long commandId, long claimerId, String step, String message,
                  String tokenDigest, String fingerprint);

    /** The final receipt state transitions from 2 to 3 or 4, with fault tolerance for 1 to 3 or 4; successful scale-in releases drain_requested in the same transaction, and terminal states are idempotent with zero side effects. */
    void complete(long commandId, long claimerId, boolean success, String resultDetail);

    /**
     * Final receipt carrying the enrollment-side identity (03 §2.9 receipts half);
     * enforcement scope mirrors the progress overload above.
     */
    void complete(long commandId, long claimerId, boolean success, String resultDetail,
                  String tokenDigest, String fingerprint);

    /** Cancellation applies only to pending dispatch (0→5); claimed commands cannot be canceled because the executor may already have started execution, and requests are rejected with a human-readable message. */
    void revokePending(long commandId);

    /** The command list is a management-plane projection sorted by id descending; an executor filter of null returns all commands. */
    List<ClusterCommandView> list(Long targetExecutorId);
}
