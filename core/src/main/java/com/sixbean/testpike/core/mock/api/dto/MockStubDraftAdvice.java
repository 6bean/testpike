package com.sixbean.testpike.core.mock.api.dto;

import com.sixbean.testpike.core.mock.domain.component.MockStubDraft;

/**
 * One preview row of the contract-bulk import wizard: every draft field of
 * the deterministic mapper plus its pre-adjudication (advice) so the wizard
 * renders selection, badges and the honest waiting label from a single row
 * read — no second call, no client-side guessing.
 *
 * @param endpointKey    contract key (METHOD + normalized path)
 * @param name           rule name (contractor title)
 * @param method         HTTP verb
 * @param pathMatchType  exact | template
 * @param pathPattern    path under the space route
 * @param matchersJson   matcher rows JSON text
 * @param responseJson   response builder document
 * @param source         provenance family
 * @param sourceRef      provenance anchor
 * @param verdict        recommend | avoid (null when no space context was given)
 * @param reason         one-sentence advice shown on the row
 * @param conflictAction skip | override | rename for conflicts; null for fresh rows
 * @param conflictStubId already-served rule a conflict points at (nullable)
 * @param impactJson     impact reading document (7-day hits, conflict name)
 * @param realism        data realism of the sample body, 0..100
 * @param confidence     confidence of the advice, 0..100
 * @param origin         rule | ai — whose advice the row currently shows
 * @param adviceState    pending | refined | degraded (null without space context)
 * @param defaultChecked wizard default selection driven by the advice verdict
 */
public record MockStubDraftAdvice(
        String endpointKey,
        String name,
        String method,
        String pathMatchType,
        String pathPattern,
        String matchersJson,
        String responseJson,
        String source,
        String sourceRef,
        String verdict,
        String reason,
        String conflictAction,
        Long conflictStubId,
        String impactJson,
        int realism,
        int confidence,
        String origin,
        String adviceState,
        boolean defaultChecked) {

    /** Backfill may still arrive before the hot deadline passes. */
    public static final String STATE_PENDING = "pending";
    /** An AI-refined advice is in place. */
    public static final String STATE_REFINED = "refined";
    /** The deadline passed without refinement — rule advice, honestly labeled. */
    public static final String STATE_DEGRADED = "degraded";

    /** Preview rows without a space context carry the draft, no advice. */
    public static java.util.List<MockStubDraftAdvice> withoutAdvice(java.util.List<MockStubDraft> drafts) {
        return drafts.stream()
                .map(d -> new MockStubDraftAdvice(d.endpointKey(), d.name(), d.method(),
                        d.pathMatchType(), d.pathPattern(), d.matchersJson(), d.responseJson(),
                        d.source(), d.sourceRef(), null, null, null, null, null,
                        0, 0, null, null, false))
                .toList();
    }
}
