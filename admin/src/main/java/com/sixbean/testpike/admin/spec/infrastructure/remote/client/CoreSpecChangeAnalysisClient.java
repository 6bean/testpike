package com.sixbean.testpike.admin.spec.infrastructure.remote.client;

import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.spec.api.dto.ApiSpecChangeAnalysisView;

import java.util.List;

/**
 * core contract change-analysis queue client (admin bridge integration boundary).
 */
public interface CoreSpecChangeAnalysisClient {

    ApiSpecChangeAnalysisView reanalyze(long newSpecId, String detectedSource);

    PageView<ApiSpecChangeAnalysisView> list(long projectId, Integer status, int page, int size);

    ApiSpecChangeAnalysisView get(long id);

    ApiSpecChangeAnalysisView getBySpec(long newSpecId);

    ApiSpecChangeAnalysisView handle(long id, String note);

    List<ApiSpecChangeAnalysisView> handleBatch(List<Long> ids, String note);
}
