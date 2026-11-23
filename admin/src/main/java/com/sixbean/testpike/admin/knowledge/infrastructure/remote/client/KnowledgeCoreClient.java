package com.sixbean.testpike.admin.knowledge.infrastructure.remote.client;

import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocDetailView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeDocView;
import com.sixbean.testpike.admin.knowledge.api.dto.KnowledgeHitView;
import com.sixbean.testpike.admin.project.api.dto.PageView;

import java.util.List;


public interface KnowledgeCoreClient {

    /**
 * The optional sort pair is forwarded to the core whitelist gate. */
    PageView<KnowledgeDocView> listDocs(Long projectId, String sourceType, String status,
                                        int page, int size, String sortBy, String sortDir);

    
    KnowledgeDocDetailView docDetail(long id);

    
    List<KnowledgeHitView> listDocHits(long id, int limit);
}
