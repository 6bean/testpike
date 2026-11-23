package com.sixbean.testpike.core.session.domain.service;

import com.sixbean.testpike.core.session.api.dto.LoginExemptInfo;
import com.sixbean.testpike.core.session.api.dto.LoginExemptInput;
import com.sixbean.testpike.core.session.api.dto.LoginExemptReviewSignal;

/**
 * Login exemption declaration ledger (AV5 registration face). The declaration
 * is a structured slot on the environment profile row; the storage/validation
 * semantics are single-sourced in LoginExemptSupport.
 */
public interface LoginExemptService {

    /** Declare or overwrite the exemption slot of one environment; echoes the new view. */
    LoginExemptInfo declare(long envId, LoginExemptInput input);

    /** Current declaration view (honest undeclared when the slot is empty). */
    LoginExemptInfo find(long envId);

    /**
     * Apply one re-check observation to the declaration slot. The verdict
     * table decides status and wording; the reviewedAt stamp is a server
     * fact, never caller-supplied. Reviewing an undeclared environment is
     * refused honestly — no declaration, nothing to vouch for.
     */
    LoginExemptInfo recordReview(long envId, LoginExemptReviewSignal signal);
}
