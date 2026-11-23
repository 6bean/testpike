package com.sixbean.testpike.aiservice.knowledge.api.controller;

import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeSearchInternalController {

    private final KnowledgeSearchService searchService;

    public KnowledgeSearchInternalController(KnowledgeSearchService searchService) {
        this.searchService = searchService;
    }

 
    @PostMapping("/search")
    public Response<List<KnowledgeSearchItem>> search(@RequestBody KnowledgeSearchRequest request) {
        return Response.builderSuccess(searchService.search(request));
    }
}
