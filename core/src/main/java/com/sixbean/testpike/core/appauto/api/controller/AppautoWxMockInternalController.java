package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoWxMockView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoWxMockService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/appauto")
public class AppautoWxMockInternalController {

    private final AppautoWxMockService wxMockService;

    public AppautoWxMockInternalController(AppautoWxMockService wxMockService) {
        this.wxMockService = wxMockService;
    }

    
    @PostMapping("/wx-mock")
    public Response<AppautoWxMockView> generate(@RequestBody AppautoWxMockRequest request) {
        return Response.builderSuccess(wxMockService.generate(request));
    }
}
