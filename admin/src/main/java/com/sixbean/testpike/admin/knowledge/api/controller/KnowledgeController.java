package com.sixbean.testpike.admin.knowledge.api.controller;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocDetailView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeHitView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeRebuildResultView;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeAiClient;
import com.sixbean.testpike.admin.knowledge.infrastructure.remote.client.KnowledgeCoreClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/knowledge")
public class KnowledgeController {

    private final KnowledgeCoreClient coreClient;
    private final KnowledgeAiClient aiClient;

    public KnowledgeController(KnowledgeCoreClient coreClient, KnowledgeAiClient aiClient) {
        this.coreClient = coreClient;
        this.aiClient = aiClient;
    }

    /**
 * The sort pair is forwarded to the core whitelist gate without a second gate. */
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/docs")
    public Response<PageView<KnowledgeDocView>> list(@RequestParam(required = false) Long projectId,
                                                     @RequestParam(required = false) String sourceType,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(
                coreClient.listDocs(projectId, sourceType, status, page, size, sortBy, sortDir));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/docs/{id}")
    public Response<KnowledgeDocDetailView> detail(@PathVariable long id) {
        return Response.builderSuccess(coreClient.docDetail(id));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @GetMapping("/docs/{id}/hits")
    public Response<List<KnowledgeHitView>> hits(@PathVariable long id,
                                                 @RequestParam(defaultValue = "20") int limit) {
        return Response.builderSuccess(coreClient.listDocHits(id, limit));
    }

    
    @RequirePermission("perm:knowledge:mcp-manage")
    @PostMapping("/rebuild")
    public Response<KnowledgeRebuildResultView> rebuild() {
        return Response.builderSuccess(aiClient.rebuild());
    }
}
