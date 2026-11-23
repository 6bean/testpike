package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoTakeoverView;
import com.sixbean.testpike.core.appauto.api.dto.TakeoverCreateInput;
import com.sixbean.testpike.core.appauto.domain.service.AppautoTakeoverService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/appauto/takeover")
public class AppautoTakeoverInternalController {

    private final AppautoTakeoverService takeoverService;

    public AppautoTakeoverInternalController(AppautoTakeoverService takeoverService) {
        this.takeoverService = takeoverService;
    }

    
    @PostMapping("/request")
    public Response<AppautoTakeoverView> request(@RequestBody TakeoverCreateInput input) {
        return Response.builderSuccess(takeoverService.request(input));
    }

    
    @PostMapping("/{id}/agree")
    public Response<AppautoTakeoverView> agree(@PathVariable long id,
                                               @RequestParam long deciderId) {
        return Response.builderSuccess(takeoverService.agree(id, deciderId));
    }

    
    @PostMapping("/{id}/reject")
    public Response<AppautoTakeoverView> reject(@PathVariable long id,
                                                @RequestParam long deciderId) {
        return Response.builderSuccess(takeoverService.reject(id, deciderId));
    }

    
    @PostMapping("/{id}/force")
    public Response<AppautoTakeoverView> force(@PathVariable long id,
                                               @RequestParam long adminOperatorId) {
        return Response.builderSuccess(takeoverService.force(id, adminOperatorId));
    }

    
    @PostMapping("/{id}/end")
    public Response<AppautoTakeoverView> end(@PathVariable long id,
                                             @RequestParam long operatorId) {
        return Response.builderSuccess(takeoverService.end(id, operatorId));
    }

    
    @GetMapping("/{id}")
    public Response<AppautoTakeoverView> get(@PathVariable long id) {
        return Response.builderSuccess(takeoverService.get(id));
    }
}
