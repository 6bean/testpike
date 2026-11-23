package com.sixbean.testpike.core.apitest.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetDetail;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPageView;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPreviewStep;
import com.sixbean.testpike.core.apitest.api.dto.RunSetCreateInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunSetService;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal endpoints for whole-collection runs ; core service layer,
 * consumed only by the admin bridge — executor traffic never reaches this port).
 * <p>Operator id arrives via X-Operator-Id (audit closure, same posture as
 * ApiRunInternalController). Validation/ownership guards stay inside the service
 * (single source); this controller only receives, delegates, wraps.
 */
@RestController
@RequestMapping("/internal/apitest/run-sets")
public class ApiRunSetInternalController {

    private final ApiRunSetService apiRunSetService;

    public ApiRunSetInternalController(ApiRunSetService apiRunSetService) {
        this.apiRunSetService = apiRunSetService;
    }

    /** Open a collection run (missing collection 404; foreign env 400; bad mode 400). */
    @PostMapping
    public Response<ApiRunSetInfo> create(@RequestBody RunSetCreateInput input) {
        return Response.builderSuccess(
                apiRunSetService.create(OperatorContext.currentOperatorId(), input));
    }

    /** Detail read-model: ledger plus every child step in seq_no order (404 when missing). */
    @GetMapping("/{id}")
    public Response<ApiRunSetDetail> detail(@PathVariable long id) {
        return Response.builderSuccess(apiRunSetService.detail(id));
    }

    /** Launch-preview: tree order + dependency arrows for one collection (404 when missing). */
    @GetMapping("/previews")
    public Response<List<ApiRunSetPreviewStep>> preview(@RequestParam long collectionId) {
        return Response.builderSuccess(apiRunSetService.preview(collectionId));
    }

    /** One paged window of the collection's run-set history, newest first. */
    @GetMapping
    public Response<ApiRunSetPageView> listByCollection(@RequestParam long collectionId,
                                                        @RequestParam(defaultValue = "1") int page,
                                                        @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(apiRunSetService.listByCollection(collectionId, page, pageSize));
    }
}
