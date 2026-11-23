package com.sixbean.testpike.core.knowledge.domain.service;

import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitInput;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitItem;

import java.util.List;


public interface KnowledgeHitService {

    
    int record(List<KnowledgeHitInput> hits);

    
    List<KnowledgeHitItem> listHits(long docId, int limit);
}
