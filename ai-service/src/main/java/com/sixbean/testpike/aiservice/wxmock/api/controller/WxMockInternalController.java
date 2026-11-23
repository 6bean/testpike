package com.sixbean.testpike.aiservice.wxmock.api.controller;

import com.sixbean.testpike.aiservice.wxmock.api.dto.WxMockRequest;
import com.sixbean.testpike.aiservice.wxmock.domain.service.WxMockService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class WxMockInternalController {

    private final WxMockService wxMockService;

    public WxMockInternalController(WxMockService wxMockService) {
        this.wxMockService = wxMockService;
    }

    
    @PostMapping("/wx-mock")
    public Response<Map<String, Object>> generate(@RequestBody WxMockRequest request) {
        return Response.builderSuccess(wxMockService.generate(request));
    }
}
