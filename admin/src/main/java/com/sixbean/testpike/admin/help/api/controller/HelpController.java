package com.sixbean.testpike.admin.help.api.controller;

import com.sixbean.testpike.admin.help.api.dto.HelpDocView;
import com.sixbean.testpike.admin.help.infrastructure.remote.client.CoreHelpClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/help/docs")
public class HelpController {

    private final CoreHelpClient coreHelpClient;

    public HelpController(CoreHelpClient coreHelpClient) {
        this.coreHelpClient = coreHelpClient;
    }

    
    @GetMapping
    public Response<List<HelpDocView>> list() {
        return Response.builderSuccess(coreHelpClient.list());
    }

    
    @GetMapping("/{domainCode}")
    public Response<List<HelpDocView>> detail(@PathVariable String domainCode) {
        return Response.builderSuccess(coreHelpClient.detail(domainCode));
    }
}
