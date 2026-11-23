package com.sixbean.testpike.core.document.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.document.api.dto.DocChangeAnalysisInfo;


public interface DocumentChangeAnalysisService {

    
    DocChangeAnalysisInfo analyze(long operatorId, long newDocumentId);

    
    DocChangeAnalysisInfo get(long id);

    
    PageResult<DocChangeAnalysisInfo> list(long projectId, Integer status, int page, int size);

    
    DocChangeAnalysisInfo handle(long operatorId, long id, String note);

    /**
 * New-version chapter body (single source = this service's private chapter
 * aggregation; consumers must never re-assemble one, R40).
 * <p>Consumed by the AL batch segment B pre-generation door: a chapter
 * missing from the snapshot's new body (deleted chapter) or carrying no
 * content returns the empty string, and the consumer names the honest
 * zero-card skip — never a silent drop.
 *
 * @param analysisId the analysis snapshot id (missing = plain-word 404)
 * @param chapter chapter key (null/blank = the whole-document block key)
 * @return the chapter body, empty string when absent
 */
    String newChapterContent(long analysisId, String chapter);
}
