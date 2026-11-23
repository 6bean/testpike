package com.sixbean.testpike.core.linkage.api.dto;

/**
 * One upstream-&gt;downstream edge as submitted to the change-linkage registry
 * (table {@code tp_change_linkage}). The registry is data, not code: wiring a
 * new entity into the change-propagation loop means inserting one row, and the
 * service re-checks here what the database constraints already enforce, so the
 * caller gets a readable refusal instead of a raw constraint violation.
 *
 * @param edgeKey               stable edge identity, e.g. {@code case->plan_case}
 * @param upstreamObjectType    object type whose save announces the change
 * @param downstreamObjectType  object type that may be invalidated by that save
 * @param detectorKind          1=spring bean detector, 2=sql predicate, 3=none (exemption)
 * @param detectorRef           detector bean name or SQL predicate text; required unless exempt
 * @param triggerFieldPredicate which upstream fields arm this edge; null=any change
 * @param dispositionStrategy   mark_only / grouped_draft / disclose_only / notify / exempt
 * @param riskLevel             1=zero-click mark/display state, 2=grouped card + human
 *                              accept, 3=disclosure only (immutable or issued record)
 * @param exempt                1=this relation deliberately does not propagate
 * @param exemptReason          written reason an exemption is auditable by
 * @param edgeNote              plain-language note of what the edge covers
 */
public record ChangeLinkageEdgeInput(
        String edgeKey,
        String upstreamObjectType,
        String downstreamObjectType,
        Integer detectorKind,
        String detectorRef,
        String triggerFieldPredicate,
        String dispositionStrategy,
        Integer riskLevel,
        Integer exempt,
        String exemptReason,
        String edgeNote) {
}
