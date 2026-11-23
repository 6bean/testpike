package com.sixbean.testpike.core.knowledge.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocDetail;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocItem;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitInput;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitItem;
import com.sixbean.testpike.core.knowledge.domain.service.KnowledgeDocService;
import com.sixbean.testpike.core.knowledge.domain.service.KnowledgeHitService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeInternalController {

    private final KnowledgeDocService knowledgeDocService;
    private final KnowledgeHitService knowledgeHitService;

    public KnowledgeInternalController(KnowledgeDocService knowledgeDocService,
                                       KnowledgeHitService knowledgeHitService) {
        this.knowledgeDocService = knowledgeDocService;
        this.knowledgeHitService = knowledgeHitService;
    }

    /**
 * The optional sort pair reaches the core column whitelist (single gate). */
    @GetMapping("/docs")
    public Response<PageResult<KnowledgeDocItem>> listDocs(
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String sortBy,
            @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                knowledgeDocService.list(projectId, sourceType, status, page, size,
                        sortBy, sortDir));
    }

    
    @GetMapping("/docs/{id}")
    public Response<KnowledgeDocDetail> getDoc(@PathVariable long id) {
        return Response.builderSuccess(knowledgeDocService.detail(id));
    }

    
    @GetMapping("/docs/{id}/hits")
    public Response<List<KnowledgeHitItem>> listDocHits(
            @PathVariable long id,
            @RequestParam(defaultValue = "20") int limit) {
        return Response.builderSuccess(knowledgeHitService.listHits(id, limit));
    }

    
    @PostMapping("/hits")
    public Response<Integer> recordHits(@RequestBody List<KnowledgeHitInput> hits) {
        return Response.builderSuccess(knowledgeHitService.record(hits));
    }
}
