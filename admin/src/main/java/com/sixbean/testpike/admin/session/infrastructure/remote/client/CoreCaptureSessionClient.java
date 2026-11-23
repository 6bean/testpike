package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.session.api.dto.CaptureSessionStartRequest;
import com.sixbean.testpike.admin.session.api.dto.CaptureSessionView;

/**
 * Bridge contract to the core capture-session service (admin side). The
 * commit round joins with the executor-side wiring package; this contract
 * covers the transient session lifecycle the wizard drives today.
 */
public interface CoreCaptureSessionClient {

    /** Open one session; returns the transient session id. */
    String openSession(CaptureSessionStartRequest request);

    /** Session metadata (core expiry wording propagates verbatim). */
    CaptureSessionView findSession(String sessionId);

    /** Explicit abandon (terminal delete on the core side). */
    void abandonSession(String sessionId);

    /**
 * Candidate checklist for the wizard commit step : metadata
 * only — the shape has no value slot, so a captured secret cannot ride
 * this bridge toward any screen (red line 3).
 */
    java.util.List<com.sixbean.testpike.admin.session.api.dto.CaptureCandidateView>
            findCandidates(String sessionId);

    /**
     * Record the checked subset and re-queue the commit signal on the same
     * hand-off queue (design doc round 3, no second channel). Returns the
     * checked count core accepted; empty-after-intersection is refused in core.
     */
    int commitSession(String sessionId, java.util.List<String> selectedNames);
}
