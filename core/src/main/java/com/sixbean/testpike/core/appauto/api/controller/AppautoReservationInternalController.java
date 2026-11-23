package com.sixbean.testpike.core.appauto.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationInput;
import com.sixbean.testpike.core.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.core.appauto.domain.service.AppautoReservationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/appauto/reservation")
public class AppautoReservationInternalController {

    private final AppautoReservationService reservationService;

    public AppautoReservationInternalController(AppautoReservationService reservationService) {
        this.reservationService = reservationService;
    }

    
    @PostMapping
    public Response<AppautoReservationView> create(@RequestBody AppautoReservationInput input) {
        return Response.builderSuccess(reservationService.create(input));
    }

    
    @GetMapping("/{id}")
    public Response<AppautoReservationView> get(@PathVariable long id) {
        return Response.builderSuccess(reservationService.get(id));
    }

    
    @GetMapping
    public Response<List<AppautoReservationView>> list(
            @RequestParam(required = false) Long deviceId,
            @RequestParam(required = false) Long projectId,
            @RequestParam(required = false) Long batchId,
            @RequestParam(required = false, defaultValue = "false") boolean includeTerminal) {
        return Response.builderSuccess(reservationService.list(deviceId, projectId, batchId,
                includeTerminal));
    }

    
    @PostMapping("/{id}/bind-batch")
    public Response<AppautoReservationView> bindBatch(@PathVariable long id,
                                                      @RequestParam(required = false) Long batchId) {
        return Response.builderSuccess(reservationService.bindBatch(id, batchId));
    }

    
    @PostMapping("/{id}/release")
    public Response<AppautoReservationView> release(@PathVariable long id,
                                                    @RequestParam(required = false) String reason) {
        return Response.builderSuccess(reservationService.release(id, reason));
    }

    
    @PostMapping("/{id}/cancel")
    public Response<AppautoReservationView> cancel(@PathVariable long id,
                                                   @RequestParam(required = false) String reason) {
        return Response.builderSuccess(reservationService.cancel(id, reason));
    }

    
    @PostMapping("/{id}/approve")
    public Response<AppautoReservationView> approve(@PathVariable long id,
                                                    @RequestParam long approverUserId) {
        return Response.builderSuccess(reservationService.approve(id, approverUserId));
    }

    
    @PostMapping("/{id}/reject")
    public Response<AppautoReservationView> reject(@PathVariable long id,
                                                   @RequestParam long approverUserId,
                                                   @RequestParam(required = false) String reason) {
        return Response.builderSuccess(reservationService.reject(id, approverUserId, reason));
    }

    
    @PostMapping("/sweep")
    public Response<Integer> sweep() {
        return Response.builderSuccess(reservationService.sweepExpired());
    }
}
