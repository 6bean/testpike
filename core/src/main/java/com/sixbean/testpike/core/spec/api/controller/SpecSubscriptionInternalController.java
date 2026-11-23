package com.sixbean.testpike.core.spec.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.core.spec.domain.service.SpecSubscriptionService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/api-spec-subscriptions")
public class SpecSubscriptionInternalController {

    private final SpecSubscriptionService subscriptionService;

    public SpecSubscriptionInternalController(SpecSubscriptionService subscriptionService) {
        this.subscriptionService = subscriptionService;
    }

    
    @PostMapping
    public Response<SpecSubscriptionInfo> create(@RequestBody SpecSubscriptionSaveRequest request) {
        return Response.builderSuccess(
                subscriptionService.create(OperatorContext.currentOperatorId(), request));
    }

    
    @GetMapping("/{id}")
    public Response<SpecSubscriptionInfo> get(@PathVariable long id) {
        return Response.builderSuccess(subscriptionService.get(id));
    }

    
    @GetMapping
    public Response<PageResult<SpecSubscriptionInfo>> list(@RequestParam long projectId,
                                                           @RequestParam(defaultValue = "1") int page,
                                                           @RequestParam(defaultValue = "20") int size) {
        return Response.builderSuccess(subscriptionService.list(projectId, page, size));
    }

    /** Edit a subscription (URL swap resets the due time so the next scan pulls
     *  immediately; renaming onto another subscription's name in the same project is a
     *  409 human-readable error; set-internal source editing flows through the whole
     *  request record — validation single source lives in the service). */
    @PutMapping("/{id}")
    public Response<SpecSubscriptionInfo> update(@PathVariable long id,
                                                 @RequestBody SpecSubscriptionSaveRequest request) {
        return Response.builderSuccess(
                subscriptionService.update(OperatorContext.currentOperatorId(), id, request));
    }

    
    @DeleteMapping("/{id}")
    public Response<Void> delete(@PathVariable long id) {
        subscriptionService.delete(OperatorContext.currentOperatorId(), id);
        return Response.builderSuccess(null);
    }
}
