package com.sixbean.testpike.core.aichat.domain.service;

import com.sixbean.testpike.core.aichat.domain.component.RevisionMergeStatus;

import java.util.Map;
import java.util.Optional;

/**
 * Write-side landing strategy behind the generic draft confirmation door
 * . One bean per proposal kind; the confirmation
 * chain itself stays shared so the guard order can never fork (red line 6).
 * <p>Implementations run AFTER the shared guards (existence → session
 * ownership → pending → ticket consumed) and own only the kind-specific
 * anchors: re-checking the optimistic version and performing the write.
 * A refusal here must leave the business table untouched.
 */
public interface DraftWriteStrategy {

    /**
 * The single "apply this version" choice a human may answer a
 * revision-merge gate CONFLICT refusal with : one-key
 * exit that replays the proposal on the row's current version so the
 * proposal's fields win over the counterpart's. A refusal burns the
 * ticket, so this choice always arrives on a fresh confirmation ticket —
 * the gate never self-selects it. Kinds without a stale-anchor shape
 * ignore it.
 */
    String ACTION_FORCE = "force";

    /** Kind token this strategy serves (matches {@code tp_ai_chat_draft.object_type}). */
    String objectType();

    /**
     * Land the confirmed proposal and return the version produced by the
     * write (NULL for kinds without a version ledger).
     *
     * @param draftRow   the pending draft row (session_id/object_type/target_id/
     *                   base_version/schema_version/payload_json/status)
     * @param operatorId the human who walked through the door
     */
    Integer land(Map<String, Object> draftRow, long operatorId);

    /**
 * Landing that also receives the human's either-or choice for
 * dual-payload drafts (create vs update on one card, AL batch ).
 * Kinds without a dual shape keep the two-argument landing untouched;
 * kinds with one must refuse a missing or unknown choice (40001) —
 * the door never guesses the reviewer's intent.
 */
    default Integer land(Map<String, Object> draftRow, long operatorId, String selectedAction) {
        return land(draftRow, operatorId);
    }

    /**
 * Queue-renewal verdict for a still-pending SIBLING card of the same
 * target, judged against the version the just-confirmed card produced
 * . Reuses the kind's own
 * revision-merge reading so the renewal never carries a second copy of
 * the intersection rules (R40).
 * <p>{@code Optional.empty} means the kind cannot reconstruct its
 * baseline here — the renewal policy then treats it conservatively as
 * BASELINE_MISSING (the card goes stale, it is never passed silently).
 * Kinds whose landing replays safely onto any current version (e.g. the
 * idempotent flaky quarantine) answer REBASE.
 */
    default Optional<RevisionMergeStatus> renewalVerdict(Map<String, Object> siblingRow,
                                                         int currentVersion) {
        return Optional.empty();
    }
}
