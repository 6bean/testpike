package com.sixbean.testpike.core.behavior.api.dto;

/**
 * One look at a template family's live gray release through the outcome
 * ledger . Produced by the rollout monitor and
 * relayed through the internal endpoint to the admin bridge; a NULL
 * {@code advisory} never happens on a gray row — an uncomputable comparison
 * comes back as an advisory whose note names the missing side, so callers can
 * distinguish "no gray release" (no view at all) from "gray release not yet
 * judgeable" (view with incomplete advisory).
 *
 * @param domain template domain of the family
 * @param name template name of the family
 * @param grayId template row id currently in gray (status 1)
 * @param controlId template row id used as the comparison control
 * (current full release, else the previous gray), NULL when
 * the family has no control at all
 * @param advisory result-rate verdict for the gray release, NULL when the
 * ledger has nothing to compare yet on either side
 */
public record BehaviorRolloutView(
        String domain,
        String name,
        Long grayId,
        Long controlId,
        BehaviorOutcomeAdvisory advisory) {
}
