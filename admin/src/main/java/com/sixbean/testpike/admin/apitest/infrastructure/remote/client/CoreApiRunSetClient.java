package com.sixbean.testpike.admin.apitest.infrastructure.remote.client;

import com.sixbean.testpike.admin.apitest.api.dto.RunSetCreateRequest;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetDetailView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPageView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetPreviewStepView;
import com.sixbean.testpike.admin.apitest.api.dto.RunSetView;

import java.util.List;

/**
 * core collection-run client .
 * Kept separate from CoreApiRunClient on purpose: per-request runs and whole-
 * collection ledgers are different contracts, one client each (no mixed duties).
 */
public interface CoreApiRunSetClient {

    RunSetView createRunSet(RunSetCreateRequest request);

    RunSetDetailView getRunSetDetail(long id);

    List<RunSetPreviewStepView> previewRunSet(long collectionId);

    RunSetPageView listRunSetsByCollection(long collectionId, int page, int pageSize);
}
