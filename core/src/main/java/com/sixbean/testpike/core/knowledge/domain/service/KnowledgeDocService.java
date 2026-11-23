package com.sixbean.testpike.core.knowledge.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocDetail;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocItem;


public interface KnowledgeDocService {

    
    PageResult<KnowledgeDocItem> list(Long projectId, String sourceType, String status,
                                      int page, int size);

    /**
 * Ledger page with server-side sorting : the sort pair is
 * resolved against the core column whitelist in SQL, so the whole filtered
 * window orders instead of the browser reshuffling one pageful. A null or
 * blank key keeps the historical id DESC default.
 *
 * @param sortBy whitelist key (id/title/source_type/project_id/status/
 * updated_at); an off-list value is refused with a human
 * 400 naming the whitelist
 * @param sortDir asc for ascending, anything else (incl. null) = desc
 */
    PageResult<KnowledgeDocItem> list(Long projectId, String sourceType, String status,
                                      int page, int size, String sortBy, String sortDir);

    
    KnowledgeDocDetail detail(long id);
}
