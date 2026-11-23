package com.sixbean.testpike.admin.tags.infrastructure.remote.client;

import com.sixbean.testpike.admin.tags.api.dto.TagStatsView;
import com.sixbean.testpike.admin.tags.api.dto.TagView;

import java.util.List;
import java.util.Map;


public interface CoreTagClient {

    
    TagView createTag(long projectId, String name, Integer type);

    
    TagView renameTag(long tagId, String newName);

    
    void mergeTags(long fromTagId, long toTagId);

 
    void deleteTag(long tagId);

    
    List<TagView> listTags(long projectId, String sortBy, String sortDir);

    
    List<TagView> listVisibleTags(List<Long> projectIds);

    
    TagView getTag(long tagId);

 
    List<TagStatsView> tagStats(long projectId);

    
    List<TagStatsView> tagStatsVisible(List<Long> projectIds);

    
    Map<Long, List<TagView>> tagsByCases(long projectId, List<Long> caseIds);

 
    List<TagView> setCaseTags(long caseId, long projectId, List<Long> tagIds);

    
    List<TagView> caseTags(long caseId);
}
