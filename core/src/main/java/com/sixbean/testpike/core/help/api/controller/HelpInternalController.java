package com.sixbean.testpike.core.help.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.help.api.dto.HelpDocInfo;
import com.sixbean.testpike.core.help.domain.service.HelpService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/internal/help/docs")
public class HelpInternalController {

    private final HelpService helpService;

    public HelpInternalController(HelpService helpService) {
        this.helpService = helpService;
    }

    
    @GetMapping
    public Response<List<HelpDocInfo>> list() {
        return Response.builderSuccess(helpService.list());
    }

    
    @GetMapping("/{domainCode}")
    public Response<List<HelpDocInfo>> detail(@PathVariable String domainCode) {
        return Response.builderSuccess(helpService.detail(domainCode));
    }

    
    @PutMapping("/version")
    public Response<Map<String, String>> refreshVersion(@RequestBody Map<String, String> body) {
        return Response.builderSuccess(Map.of("version",
                helpService.refreshVersion(body.get("version"))));
    }
}
