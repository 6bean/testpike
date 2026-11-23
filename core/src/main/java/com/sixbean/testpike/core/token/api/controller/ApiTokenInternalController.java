package com.sixbean.testpike.core.token.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyRequest;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyResult;
import com.sixbean.testpike.core.token.api.dto.ApiTokenView;
import com.sixbean.testpike.core.token.domain.service.ApiTokenService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/api-tokens")
public class ApiTokenInternalController {

    private final ApiTokenService apiTokenService;

    public ApiTokenInternalController(ApiTokenService apiTokenService) {
        this.apiTokenService = apiTokenService;
    }

    
    @GetMapping
    public Response<List<ApiTokenView>> list() {
        return Response.builderSuccess(
                apiTokenService.listByUser(OperatorContext.currentOperatorId()));
    }

    
    @PostMapping
    public Response<ApiTokenCreated> create(@RequestBody ApiTokenCreateInput input) {
        return Response.builderSuccess(
                apiTokenService.create(OperatorContext.currentOperatorId(), input));
    }

    
    @DeleteMapping("/{id}/revoke")
    public Response<Void> revoke(@PathVariable long id) {
        apiTokenService.revoke(id, OperatorContext.currentOperatorId());
        return Response.builderSuccess(null);
    }

    
    @PostMapping("/verify")
    public Response<ApiTokenVerifyResult> verify(@RequestBody ApiTokenVerifyRequest request) {
        return Response.builderSuccess(
                apiTokenService.verify(request == null ? null : request.plaintext()));
    }
}
