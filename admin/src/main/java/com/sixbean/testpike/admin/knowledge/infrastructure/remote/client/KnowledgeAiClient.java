package com.sixbean.testpike.admin.knowledge.infrastructure.remote.client;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeRebuildResultView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeSearchHit;

import java.util.List;


public interface KnowledgeAiClient {

    
    KnowledgeRebuildResultView rebuild();

    
    List<KnowledgeSearchHit> search(Long projectId, String query, String scene, Integer topK);
}
