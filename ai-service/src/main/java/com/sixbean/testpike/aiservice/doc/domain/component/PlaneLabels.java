package com.sixbean.testpike.aiservice.doc.domain.component;

/**
 * Bundle of tp_ai_call_log.function labels identifying the three extraction
 * plane roles (final extract / F5 group summary / reconcile gap top-up). The
 * vocabulary itself lives in the service seam that books the call-log rows —
 * this value object only carries the references, so the fidelity ledger never
 * keeps a second copy of the labels (single source).
 */
public record PlaneLabels(String extractPlane, String summaryPlane, String gapPlane) {
}
