package com.sixbean.testpike.core.linkage.api.dto;

import java.util.List;

/**
 * Outcome counters of one downstream detection pass (a single
 * {@code {objectType}.changed} announcement). The numbers are the detection
 * account the batch design requires (the detection ledger is always emitted):
 * a save that found no
 * impact must stay silent in the user-facing ledger but must still be
 * reportable here, and the reconcile job compares these walks against the
 * edge registry.
 *
 * @param edgesChecked    active non-exempt edges examined for this member
 * @param impactsCreated  new ledger rows written by this pass
 * @param impactsUpdated  existing rows re-armed by a newer upstream version
 *                        (a replay of the same version changes nothing)
 * @param downstreamFound total downstream rows the detectors matched
 * @param pendingDrafts   drafts armed by THIS pass as {@code type:id} keys
 *                        (grouped-draft rows newly created or re-armed by a
 *                        newer version; a replay of the same version arms
 *                        none, so the repair-chain trigger never double-asks)
 */
public record LinkageImpactSummary(
        int edgesChecked,
        int impactsCreated,
        int impactsUpdated,
        int downstreamFound,
        List<String> pendingDrafts) {
}
