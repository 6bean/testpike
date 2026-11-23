package com.sixbean.testpike.core.delreq.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestCreateInput;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestRejectInput;


public interface DeleteRequestService {

    
    DeleteRequestInfo create(Long applicantId, DeleteRequestCreateInput input);

    
    DeleteRequestInfo approve(Long operatorId, long id);

    
    DeleteRequestInfo reject(Long operatorId, long id, DeleteRequestRejectInput input);

    
    DeleteRequestInfo get(long id);

    
    PageResult<DeleteRequestInfo> list(long projectId, Integer status, int page, int size);

    /**
 * Project-scoped list with a server-side sort :
 * {@code sortBy} must be a whitelisted ledger column or the shared
 * sort-column gate refuses it fail-closed with a human 400 before any
 * read; null/blank keeps the historical id DESC order. Every whitelisted
 * key carries a constant id DESC tie-break so equal values page
 * deterministically. The four-argument entry above delegates here with
 * nulls — zero drift for existing callers.
 */
    PageResult<DeleteRequestInfo> list(long projectId, Integer status, int page, int size,
                                       String sortBy, String sortDir);
}
