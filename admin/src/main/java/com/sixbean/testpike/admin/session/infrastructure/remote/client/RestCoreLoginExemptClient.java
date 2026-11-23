package com.sixbean.testpike.admin.session.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptDeclareRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptReviewRequest;
import com.sixbean.testpike.admin.session.api.dto.LoginExemptView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * RestClient implementation of the login-exemption bridge (RestCoreLoginStateClient
 * idiom — X-Operator-Id pass-through, core wording propagated verbatim, no
 * second validation on this side). LoginExemptView and the core record share
 * field names for direct mapping; the three-door surface mirrors
 * CoreLoginExemptClient and stays reveal-free (red line 3).
 */
@Component
public class RestCoreLoginExemptClient implements CoreLoginExemptClient {

    private static final String BASE = "/internal/login-exempt";

    private final RestClient rest;

    public RestCoreLoginExemptClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public LoginExemptView find(long envId) {
        return RestClientInvoker.call(rest, HttpMethod.GET, BASE + "/" + envId, null,
                new ParameterizedTypeReference<Response<LoginExemptView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public LoginExemptView declare(long envId, LoginExemptDeclareRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.PUT, BASE + "/" + envId, request,
                new ParameterizedTypeReference<Response<LoginExemptView>>() {
                }, UserContext.currentUserId());
    }

    @Override
    public LoginExemptView recordReview(long envId, LoginExemptReviewRequest request) {
        return RestClientInvoker.call(rest, HttpMethod.POST, BASE + "/" + envId + "/review", request,
                new ParameterizedTypeReference<Response<LoginExemptView>>() {
                }, UserContext.currentUserId());
    }
}
