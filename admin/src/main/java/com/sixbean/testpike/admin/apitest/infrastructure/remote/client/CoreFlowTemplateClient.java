package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplatePageDto;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateSaveRequest;
import com.sixbean.testpike.admin.apitest.api.dto.FlowTemplateView;
import com.sixbean.testpike.admin.apitest.api.dto.RequestView;

import java.util.List;

/**
 * core flow-template client .
 * Separate from CoreApiRunSetClient on purpose: the reusable snapshot library
 * and the run ledger are different contracts, one client each.
 */
public interface CoreFlowTemplateClient {

    FlowTemplateView saveTemplate(FlowTemplateSaveRequest request);

    FlowTemplateView getTemplate(long id);

    FlowTemplatePageDto listTemplates(long projectId, int page, int pageSize);

    List<RequestView> instantiateTemplate(long id, long targetCollectionId);

    void deleteTemplate(long id);
}
