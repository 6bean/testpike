package com.sixbean.testpike.admin.cost.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideInput;
import com.sixbean.testpike.admin.cost.api.dto.BreakOverrideView;
import com.sixbean.testpike.admin.cost.api.dto.BudgetInput;
import com.sixbean.testpike.admin.cost.api.dto.BudgetView;
import com.sixbean.testpike.admin.cost.api.dto.CostOverviewView;
import com.sixbean.testpike.common.client.RestClientInvoker;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


@Component
public class RestCoreCostClient implements CoreCostClient {

    private final RestClient rest;

    public RestCoreCostClient(@Value("${core.base-url:http://localhost:8083}") String coreBaseUrl) {
        this.rest = RestClientInvoker.defaultRestClient(coreBaseUrl);
    }

    @Override
    public CostOverviewView overview(String month, Long projectId) {
        String url = "/internal/cost/overview?month=" + month
                + (projectId == null ? "" : "&projectId=" + projectId);
        return call(HttpMethod.GET, url, null, new ParameterizedTypeReference<>() {
        });
    }

    @Override
    public BudgetView getBudget(long projectId) {
        return call(HttpMethod.GET, "/internal/cost/budget/" + projectId, null,
                new ParameterizedTypeReference<>() {
                });
    }

    @Override
    public BudgetView putBudget(long operatorId, long projectId, BudgetInput input) {
        return RestClientInvoker.call(rest, HttpMethod.PUT, "/internal/cost/budget/" + projectId,
                input, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    @Override
    public BreakOverrideView breakOverride(long operatorId, long projectId, BreakOverrideInput input) {
        return RestClientInvoker.call(rest, HttpMethod.POST, "/internal/cost/break-override/" + projectId,
                input, new ParameterizedTypeReference<>() {
                }, operatorId);
    }

    private <T> T call(HttpMethod method, String uri, Object body,
                       ParameterizedTypeReference<Response<T>> type) {
        return RestClientInvoker.call(rest, method, uri, body, type, UserContext.currentUserId());
    }
}
