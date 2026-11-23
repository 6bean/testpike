package com.sixbean.testpike.aiservice.knowledge.api.controller;

import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeReconcileResult;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeReconcileService;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/internal/knowledge")
public class KnowledgeReconcileInternalController {

    private final KnowledgeReconcileService reconcileService;

    public KnowledgeReconcileInternalController(KnowledgeReconcileService reconcileService) {
        this.reconcileService = reconcileService;
    }

    
    @PostMapping("/reconcile")
    public Response<KnowledgeReconcileResult> reconcile() {
        return Response.builderSuccess(reconcileService.reconcile());
    }

    
    @PostMapping("/rebuild")
    public Response<KnowledgeReconcileResult> rebuild() {
        return Response.builderSuccess(reconcileService.rebuildAll());
    }
}
