package com.sixbean.testpike.common.linkage;

/**
 * One downstream row the linkage bus found when an upstream object was saved
 * (table {@code tp_linkage_impact}). This ledger is the single display surface
 * for "saving X touched these downstream objects": the stale mark, the
 * grouped-card pending draft and the issued-record disclosure are all just
 * rows here with a different disposition strategy — no downstream business
 * table is rewritten by detection itself (design adjudication section 3.1).
 *
 * @param id                  row id
 * @param edgeKey             ledger key of the edge that found this row
 * @param upstreamObjectType  bus token of the saved object, e.g. {@code case}
 * @param upstreamObjectId    the object that was saved
 * @param upstreamVersion     version of the upstream row this impact reflects
 *                            (idempotency anchor: replaying it changes nothing)
 * @param downstreamObjectType invalidated object type
 * @param downstreamId        invalidated object row id
 * @param dispositionStrategy mark_only / grouped_draft / disclose_only / notify
 * @param status              1=stale mark, 2=draft pending, 3=disclosed,
 *                            4=consumed (accepted or acknowledged)
 * @param annotatedAt         when this impact was last raised
 * @param consumedAt          when a human/automation consumed it (null=still pending)
 */
public record LinkageImpactInfo(
        Long id,
        String edgeKey,
        String upstreamObjectType,
        Long upstreamObjectId,
        Integer upstreamVersion,
        String downstreamObjectType,
        Long downstreamId,
        String dispositionStrategy,
        Integer status,
        java.time.LocalDateTime annotatedAt,
        java.time.LocalDateTime consumedAt) {
}
