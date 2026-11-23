package com.sixbean.testpike.aiservice.popuplearn.api.controller;

import com.sixbean.testpike.aiservice.popuplearn.api.dto.PopupLearnRequest;
import com.sixbean.testpike.aiservice.popuplearn.domain.service.PopupLearnService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/internal/appauto")
public class PopupLearnInternalController {

    private final PopupLearnService popupLearnService;

    public PopupLearnInternalController(PopupLearnService popupLearnService) {
        this.popupLearnService = popupLearnService;
    }

    
    @PostMapping("/popup-learn")
    public Response<Map<String, Object>> learn(@RequestBody PopupLearnRequest request) {
        return Response.builderSuccess(popupLearnService.learn(request));
    }
}
