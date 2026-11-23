package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.session.api.dto.LoginExemptDeclareRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptReviewRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptView;

/**
 * core login-exemption bridge client (admin → core /internal/login-exempt
 * pass-through, RestCoreLoginStateClient idiom). Exactly three doors: read,
 * declare, record a re-check. There is no reveal door by construction —
 * the vault value has no path through this face (red line 3).
 */
public interface CoreLoginExemptClient {

    /** Current declaration view (missing environment = core wording propagates). */
    LoginExemptView find(long envId);

    /** Declare or overwrite the exemption slot (rules single-sourced in core). */
    LoginExemptView declare(long envId, LoginExemptDeclareRequest request);

    /** Apply one re-check observation; the verdict table runs inside core. */
    LoginExemptView recordReview(long envId, LoginExemptReviewRequest request);
}
