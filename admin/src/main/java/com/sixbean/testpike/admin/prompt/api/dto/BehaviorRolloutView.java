package com.sixbean.testpike.admin.prompt.api.dto;

/**
 * Admin mirror of core BehaviorRolloutView ( item 7 gray-rollout advisory;
 * five fields kept whole — no cross-module reference of core types, the
 * PromptTemplateView double-mirror precedent applies). NULL on the wire means
 * the template family has no live gray release; a present view with a NULL
 * {@code advisory} never happens on a gray row (core answers an uncomputable
 * comparison with an advisory whose note names the missing side).
 *
 * @param domain template domain of the family
 * @param name template name of the family
 * @param grayId template row id currently in gray (status 1)
 * @param controlId template row id used as the comparison control, NULL when
 * the family has no control at all
 * @param advisory result-rate verdict for the gray release, NULL when the
 * ledger has nothing to compare yet on either side
 */
public record BehaviorRolloutView(
        String domain,
        String name,
        Long grayId,
        Long controlId,
        BehaviorOutcomeAdvisoryView advisory) {
}
