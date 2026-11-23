package com.sixbean.testpike.core.spec.domain.service;

import com.sixbean.testpike.core.spec.api.dto.ApiSpecPreviewInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecPreviewApplyResult;
import com.sixbean.testpike.core.spec.domain.component.ApiSpecDraftItem;

import java.util.List;


public interface ApiSpecPreviewService {

    
    ApiSpecPreviewInfo generate(Long operatorId, long specId);

    
    ApiSpecPreviewInfo generateFromDrafts(Long operatorId, long documentId,
                                          List<ApiSpecDraftItem> rawItems);

    
    ApiSpecPreviewInfo get(long previewId);

    
    ApiSpecPreviewInfo latestByDocument(long documentId);

    
    SpecPreviewApplyResult apply(Long operatorId, long previewId, List<String> selectedKeys);
}
