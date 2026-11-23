package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoDeviceThumbnailView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoDeviceThumbnailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Device-wall thumbnail internal endpoint (stage-two P1③ read face, served to the
 * admin bridge over HTTP; AppautoTakeoverInternalController thin-forward style).
 * <p>Zero business semantics here: frame sourcing, downscale, short cache and the
 * honest IDLE/NO_FRAME cells all live in {@link AppautoDeviceThumbnailService};
 * the only error is an unknown device, raised as a plain-language 404 by the
 * service and passed through untouched (message single-source rule — a second
 * verdict chain in this controller is forbidden).
 * <p>Internal paths are exempt from the 02 endpoint registry (existing rule);
 * permission (project view / shared-slot visibility) is enforced on the admin bridge.
 */
@RestController
@RequestMapping("/internal/appauto")
public class AppautoDeviceThumbnailInternalController {

    private final AppautoDeviceThumbnailService thumbnailService;

    public AppautoDeviceThumbnailInternalController(AppautoDeviceThumbnailService thumbnailService) {
        this.thumbnailService = thumbnailService;
    }

    /** Current wall cell for one device (LIVE frame or honest IDLE/NO_FRAME view). */
    @GetMapping("/devices/{deviceId}/thumbnail")
    public Response<AppautoDeviceThumbnailView> thumbnail(@PathVariable long deviceId) {
        return Response.builderSuccess(thumbnailService.thumbnail(deviceId));
    }
}
