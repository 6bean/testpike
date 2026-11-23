package com.sixbean.testpike.core.webelement.api.dto;

/**
 * Evidence-plane baseline grant for the Page Element Library re-anchor
 * compare port (design 8.1 scale B, evidence JSONB column of V544).
 * <p>found=true only when the ledger row carries a usable screenshotKey;
 * url is the presigned GET for that object, null when storage grants are
 * unavailable (the executor then reads similarity as unprovable and keeps
 * the proposal route — never an implicit pass). revision mirrors the LIVE
 * ledger row so callers can spot claim-envelope drift.
 * <p>Deliberately separate from WebElementView: the list-display contract
 * stays unpolluted (no frontend surface grows for this internal channel).
 */
public record ElementEvidenceGrantView(boolean found, String screenshotKey,
                                       String url, Long revision) {
}
