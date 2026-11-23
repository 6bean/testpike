package com.sixbean.testpike.core.prompt.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateFamilyItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateItem;
import com.sixbean.testpike.core.prompt.api.dto.PromptTemplateSaveInput;
import com.sixbean.testpike.core.prompt.domain.service.PromptTemplateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/prompt-templates")
public class PromptTemplateInternalController {

    private final PromptTemplateService promptTemplateService;

    public PromptTemplateInternalController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    
    @PostMapping
    public Response<PromptTemplateItem> save(@RequestBody PromptTemplateSaveInput input) {
        return Response.builderSuccess(promptTemplateService.save(input));
    }

    
    @PostMapping("/{sourceId}/rollback")
    public Response<PromptTemplateItem> rollback(@PathVariable Long sourceId,
                                                 @RequestParam(required = false) Long operatorId) {
        return Response.builderSuccess(promptTemplateService.rollback(sourceId, operatorId));
    }

    
    @GetMapping("/{id}")
    public Response<PromptTemplateItem> detail(@PathVariable Long id) {
        return Response.builderSuccess(promptTemplateService.detail(id));
    }

    
    @GetMapping("/versions")
    public Response<List<PromptTemplateItem>> listVersions(@RequestParam String name,
                                                           @RequestParam String domain) {
        return Response.builderSuccess(promptTemplateService.listVersions(name, domain));
    }

    /**
     * Family enumeration (one newest-version row per domain+name pair) — the "which prompt
     * templates exist" read that the version-by-name queries cannot answer on their own.
     */
    @GetMapping("/families")
    public Response<List<PromptTemplateFamilyItem>> listFamilies(
            @RequestParam(required = false) String domain) {
        return Response.builderSuccess(promptTemplateService.listFamilies(domain));
    }

    
    @GetMapping("/latest")
    public Response<PromptTemplateItem> latest(@RequestParam String name,
                                               @RequestParam String domain) {
        return Response.builderSuccess(promptTemplateService.latest(name, domain));
    }

    
    @GetMapping("/latest-by-status")
    public Response<PromptTemplateItem> latestByStatus(@RequestParam String name,
                                                       @RequestParam String domain,
                                                       @RequestParam int status) {
        return Response.builderSuccess(promptTemplateService.latestByStatus(name, domain, status));
    }

    
    @PostMapping("/{id}/promote")
    public Response<PromptTemplateItem> promote(@PathVariable Long id) {
        return Response.builderSuccess(promptTemplateService.promote(id));
    }
}
