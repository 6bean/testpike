package com.sixbean.testpike.core.session.api.dto;

import java.util.List;

/**
 * Executor answer for one capture round . A round carries either
 * candidate metadata, or an honest failure reason the operator will see in the
 * wizard, or nothing at all (the human has not finished logging in yet — the
 * session is re-queued instead of being killed).
 *
 * <p>The reason stays inside the transient session payload: the wizard read
 * shape exposes only the status word, so an executor-side sentence can never
 * turn into an unreviewed screen string (red line 4 family).
 *
 * @param executorId executor that ran the round (observability, no authority)
 * @param failureReason honest failure sentence; blank when candidates came back
 * @param candidates scoped candidate rows (metadata only, no values)
 */
public record CaptureSessionReport(
        long executorId,
        String failureReason,
        List<CaptureCandidateMetadata> candidates) {
}
