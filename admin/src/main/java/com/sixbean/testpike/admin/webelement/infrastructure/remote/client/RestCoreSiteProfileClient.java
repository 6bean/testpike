package com.sixbean.testpike.admin.webelement.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.admin.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.common.webelement.SiteSurveyRunView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/**
 * RestClient implementation of the site-profile client. Operator id rides on
 * X-Operator-Id (audit closure) and core error codes pass through unchanged,
 * exactly like the element-ledger client it sits beside.
 */
@Component
public class RestCoreSiteProfileClient implements CoreSiteProfileClient {

    private final RestClient rest;

    public RestCoreSiteProfileClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public SiteProfileView create(SiteProfileInput input) {
        return call(HttpMethod.POST, "/internal/site-profiles", input,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SiteProfileView get(long id) {
        return call(HttpMethod.GET, "/internal/site-profiles/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<SiteProfileView> list(long projectId) {
        return call(HttpMethod.GET, "/internal/site-profiles?projectId=" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public SiteProfileView update(long id, SiteProfileInput input) {
        return call(HttpMethod.PUT, "/internal/site-profiles/" + id, input,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public void delete(long id) {
        call(HttpMethod.DELETE, "/internal/site-profiles/" + id, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public List<SiteSurveyRunView> listRuns(long projectId, int limit) {
        return call(HttpMethod.GET,
                "/internal/site-survey-runs?projectId=" + projectId + "&limit=" + limit, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public long dispatchSurvey(long projectId, long profileId) {
        Long runId = call(HttpMethod.POST,
                "/internal/site-survey-runs/" + profileId + "/dispatch?projectId=" + projectId,
                null, new ParameterizedTypeReference<>() {
                });
        if (runId == null) {
            throw new ServiceException("整站测绘派发未返回运行编号：请到「站点档案 → 测绘记录」"
                    + "确认该站点是否已有进行中的测绘，并从档案行重新发起整站测绘。",
                    BizErrorCode.INTERNAL_ERROR);
        }
        return runId;
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<com.sixbean.testpike.common.clientobject.Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
