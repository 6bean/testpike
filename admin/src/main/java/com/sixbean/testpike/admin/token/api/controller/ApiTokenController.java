package com.sixbean.testpike.admin.token.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreateRequest;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenCreatedView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenUsageView;
import com.sixbean.testpike.admin.token.api.dto.ApiTokenView;
import com.sixbean.testpike.admin.token.infrastructure.remote.client.ApiTokenClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/api-tokens")
public class ApiTokenController {

    private final ApiTokenClient apiTokenClient;

    public ApiTokenController(ApiTokenClient apiTokenClient) {
        this.apiTokenClient = apiTokenClient;
    }

    
    @GetMapping
    public Response<List<ApiTokenView>> list() {
        return Response.builderSuccess(apiTokenClient.listByUser(UserContext.currentUserId()));
    }

    
    @PostMapping
    public Response<ApiTokenCreatedView> create(@RequestBody ApiTokenCreateRequest request) {
        return Response.builderSuccess(
                apiTokenClient.create(UserContext.currentUserId(), request));
    }

    
    @DeleteMapping("/{id}/revoke")
    public Response<Void> revoke(@PathVariable long id) {
        apiTokenClient.revoke(id, UserContext.currentUserId());
        return Response.builderSuccess(null);
    }

    /** GA3 usage roll-up (calls / failures / p95 duration) for one token; owner ∪ admin read-through is decided once in core — the bridge stays a pure pass-through. */
    @GetMapping("/{id}/usage")
    public Response<ApiTokenUsageView> usage(@PathVariable long id) {
        return Response.builderSuccess(
                apiTokenClient.usage(id, UserContext.currentUserId()));
    }
}
