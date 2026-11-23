package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementCaptureRequest;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementPageView;
import com.sixbean.testpike.core.appauto.api.dto.AppautoElementView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementCaptureService;
import com.sixbean.testpike.core.appauto.domain.service.AppautoElementService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Element library internal API (bridged by the admin /api/v1/appauto/elements
 * surface; frontends never call core directly per the layering rule, same
 * posture as AppautoPopupRuleInternalController; internal routes are exempt
 * from the OpenAPI user registry).
 * <p>Thin controller: every validation, uniqueness conflict and 404 wording
 * lives in AppautoElementService (single validation point, no second set).
 */
@RestController
@RequestMapping("/internal/appauto")
public class AppautoElementInternalController {

    private final AppautoElementService elementService;
    private final AppautoElementCaptureService captureService;

    public AppautoElementInternalController(AppautoElementService elementService,
                                            AppautoElementCaptureService captureService) {
        this.elementService = elementService;
        this.captureService = captureService;
    }

    /** Paginated listing, newest-first, optional keyword/type filters. */
    @GetMapping("/elements")
    public Response<AppautoElementPageView> page(@RequestParam long projectId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String locatorType,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return Response.builderSuccess(
                elementService.page(projectId, keyword, locatorType, page, pageSize));
    }

    /** Single row read (missing = human 404 passthrough). */
    @GetMapping("/elements/{id}")
    public Response<AppautoElementView> find(@PathVariable long id) {
        return Response.builderSuccess(elementService.find(id));
    }

    /** Create one element profile; returns the new id. */
    @PostMapping("/elements")
    public Response<Long> create(@RequestParam long projectId,
                                 @RequestBody AppautoElementInput input) {
        return Response.builderSuccess(elementService.create(projectId, input));
    }

    /** Full update of one element profile. */
    @PutMapping("/elements/{id}")
    public Response<AppautoElementView> update(@PathVariable long id,
                                               @RequestBody AppautoElementInput input) {
        return Response.builderSuccess(elementService.update(id, input));
    }

    /** Delete one element profile. */
    @DeleteMapping("/elements/{id}")
    public Response<Void> delete(@PathVariable long id) {
        elementService.delete(id);
        return Response.builderSuccess(null);
    }

    /**
     * Collaborative capture: crop one region out of a buffered live frame and
     * file it as an IMAGE element profile with scaled variants. Returns the new
     * element id; crop/region/frame errors stay in the capture service wording.
     */
    @PostMapping("/elements/capture")
    public Response<Long> capture(@RequestParam long projectId,
                                  @RequestBody AppautoElementCaptureRequest request) {
        return Response.builderSuccess(captureService.captureFromFrame(projectId, request));
    }
}
