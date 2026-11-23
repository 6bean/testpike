package com.sixbean.testpike.core.tags.domain.service;

import com.sixbean.testpike.core.tags.api.dto.CaseTagSetInput;
import com.sixbean.testpike.core.tags.api.dto.TagCreateInput;
import com.sixbean.testpike.core.tags.api.dto.TagInfo;
import com.sixbean.testpike.core.tags.api.dto.TagMergeInput;
import com.sixbean.testpike.core.tags.api.dto.TagRenameInput;
import com.sixbean.testpike.core.tags.api.dto.TagStats;

import java.util.List;
import java.util.Map;


public interface TagService {

    
    TagInfo create(Long operatorId, TagCreateInput input);

    
    TagInfo rename(Long operatorId, long tagId, TagRenameInput input);

    
    void merge(Long operatorId, TagMergeInput input);

 
    void delete(Long operatorId, long tagId);

    
    List<TagInfo> list(long projectId);

    /**
     * Sorted tag list (table-feature baseline: ORDER BY happens in SQL through
     * a physical-column whitelist, never in the browser over one pageful).
     *
     * @param sortBy  whitelist key (name/type/created_at/case_count);
     *                null/blank keeps the historical id-ASC default;
     *                off-whitelist key fails closed with a human 400
     * @param sortDir asc = ascending, anything else = descending
     */
    List<TagInfo> list(long projectId, String sortBy, String sortDir);

    
    List<TagInfo> listVisible(List<Long> projectIds);

    
    TagInfo get(long tagId);

    /**
 * Raw optimistic version of a tag-dictionary row (AM batch
 * the dialog door re-anchors rename/merge proposals onto the live value).
 * Returns null when the row is gone — the caller skips the anchor honestly.
 */
    Integer currentVersion(long tagId);

    
    List<TagStats> stats(long projectId);

    
    List<TagStats> statsVisible(List<Long> projectIds);

 
    List<TagInfo> setCaseTags(Long operatorId, long caseId, CaseTagSetInput input);

    
    List<TagInfo> caseTags(long caseId);

    
    Map<Long, List<TagInfo>> tagsByCases(long projectId, List<Long> caseIds);
}
