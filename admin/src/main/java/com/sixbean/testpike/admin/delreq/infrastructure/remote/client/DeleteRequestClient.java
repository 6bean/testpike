package com.sixbean.testpike.admin.delreq.infrastructure.remote.client;

import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestCreateRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestRejectRequest;
import com.sixbean.testpike.admin.delreq.api.dto.DeleteRequestView;
import com.sixbean.testpike.admin.project.api.dto.PageView;


public interface DeleteRequestClient {

    /**
     * Project-scoped list (H1 isolation stays double-layer: admin guard +
     * core project_id filter; null status = all). The sortBy/sortDir pair is
     * forwarded untouched to the core sort whitelist.
     */
    PageView<DeleteRequestView> list(long projectId, Integer status, int page, int size,
                                     String sortBy, String sortDir, long operatorId);

    
    DeleteRequestView create(long operatorId, DeleteRequestCreateRequest request);

    
    DeleteRequestView get(long id, long operatorId);

    
    DeleteRequestView approve(long id, long operatorId);

    
    DeleteRequestView reject(long id, long operatorId, DeleteRequestRejectRequest request);
}
