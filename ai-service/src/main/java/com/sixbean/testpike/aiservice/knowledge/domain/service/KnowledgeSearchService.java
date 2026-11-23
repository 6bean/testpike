package com.sixbean.testpike.aiservice.knowledge.domain.service;

import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;

import java.util.List;


public interface KnowledgeSearchService {

    
    List<KnowledgeSearchItem> search(KnowledgeSearchRequest request);
}
