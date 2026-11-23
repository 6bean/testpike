package com.sixbean.testpike.core.appauto.domain.service;

import com.sixbean.testpike.core.appauto.api.dto.AppautoElementCaptureRequest;

/**
 * Collaborative capture for the element library (W5 stage-two device picture
 * capture): pick one buffered live frame, crop the region the operator selected
 * on screen, store the crop at several scales as archived images and create the
 * image element profile that references them.
 * <p>Frame source is the existing monitor ring buffer (no second real-time
 * protocol); archive write goes through the element library service so shape
 * validation and the per-project unique name rule stay single-sourced. Runtime
 * locator resolution still lives on the executor side.
 */
public interface AppautoElementCaptureService {

    /**
     * Capture one region and file it as an image element.
     *
     * @param projectId owning project (resolved by the caller, drives the
     *                  storage route and the element archive row)
     * @param request   frame anchor, crop rectangle and variant scales
     * @return id of the newly created element profile
     */
    long captureFromFrame(long projectId, AppautoElementCaptureRequest request);
}
