package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeReconcileResult;


public interface KnowledgeReconcileService {

    
    KnowledgeReconcileResult reconcile();

 
    KnowledgeReconcileResult rebuildAll();
}
