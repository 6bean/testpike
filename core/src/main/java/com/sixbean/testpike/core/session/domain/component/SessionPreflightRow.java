package com.sixbean.testpike.core.session.domain.component;

import com.sixbean.testpike.core.executor.domain.component.SessionStateDeclaration;

/**
 * One batch case row entering the login-state preflight (AV3 trigger ①).
 *
 * <p>The case-side selector comes from the action-JSON declaration parsed at
 * the single read point; the resolved ledger row contributes its state name
 * and four-state expiry light, both computed upstream — this component never
 * re-derives or re-parses expiry material (single-source rule for the light).
 *
 * <p>A declared case whose resolution produced no state name (missing or
 * unusable ledger row) still arrives here with a null resolved name so the
 * refusal can name the case and the selector it asked for.
 *
 * @param caseNo            human-facing case number of the batch member
 * @param title             case title shown in the refusal listing
 * @param declaration       parsed sessionState selector, null when the case declares nothing
 * @param resolvedStateName ledger row name the selector resolved to, null when unresolved
 * @param expiryState       four-state light of the resolved row, null when unresolved
 */
public record SessionPreflightRow(String caseNo,
                                  String title,
                                  SessionStateDeclaration declaration,
                                  String resolvedStateName,
                                  ExpiryState expiryState) {
}
