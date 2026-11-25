package com.sixbean.testpike.admin.mock.api.dto;

/**
 * Admin mirror of core's advice-joined contract-import preview row (MJ P3):
 * every draft field of the deterministic mapper plus its pre-adjudication, so
 * the wizard renders the default selection, the one-sentence advice and the
 * honest waiting label from a single row read. A field-for-field mirror —
 * the bridge forwards it untouched and re-decides nothing (red line 6): the
 * advice floor, the deadline band and the default-check rule are all core's
 * single point, and a row is still only a proposal — importing goes through
 * the human confirm gate unchanged.
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
 * @param reason         one-sentence advice shown on the row (core wording)
 * @param conflictAction skip | override | rename for conflicts; null for fresh rows
 * @param conflictStubId already-served rule a conflict points at (nullable)
 * @param impactJson     impact reading document (7-day hits, conflict name)
 * @param realism        data realism of the sample body, 0..100
 * @param confidence     confidence of the advice, 0..100
 * @param origin         rule | ai — whose advice the row currently shows
 * @param adviceState    pending | refined | degraded (null without space context)
 * @param defaultChecked wizard default selection driven by core's advice verdict
 */
public record MockStubDraftAdviceView(
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
}
