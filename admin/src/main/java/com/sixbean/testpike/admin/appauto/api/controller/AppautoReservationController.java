package com.sixbean.testpike.admin.appauto.api.controller;

import com.sixbean.testpike.admin.appauto.api.dto.AppautoReservationView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCalendarView;
import com.sixbean.testpike.admin.appauto.api.dto.ReservationCreateRequest;
import com.sixbean.testpike.admin.appauto.app.service.AppautoReservationAppService;
import com.sixbean.testpike.admin.appauto.infrastructure.remote.client.CoreAppautoClient;
import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.project.app.component.ProjectAccessGuard;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;


@RestController
@RequestMapping("/api/v1/appauto/reservation")
public class AppautoReservationController {

    private final CoreAppautoClient coreAppautoClient;
    private final ProjectAccessGuard guard;
    private final AppautoReservationAppService reservationAppService;

    public AppautoReservationController(CoreAppautoClient coreAppautoClient,
                                        ProjectAccessGuard guard,
                                        AppautoReservationAppService reservationAppService) {
        this.coreAppautoClient = coreAppautoClient;
        this.guard = guard;
        this.reservationAppService = reservationAppService;
    }

    
    @PostMapping
    @RequirePermission("perm:appauto:reservation:create")
    public Response<AppautoReservationView> create(@RequestBody ReservationCreateRequest request) {
        if (request.projectId() != null) {
            guard.assertCanView(UserContext.current(), request.projectId());
        }
        return Response.builderSuccess(coreAppautoClient.createReservation(
                request, UserContext.currentUserId()));
    }

    
    @GetMapping("/{id}")
    @RequirePermission("perm:appauto:reservation:view")
    public Response<AppautoReservationView> get(@PathVariable long id) {
        AppautoReservationView row = coreAppautoClient.findReservation(id);
        applyViewGate(row);
        return Response.builderSuccess(row);
    }

    
    @GetMapping
    @RequirePermission("perm:appauto:reservation:view")
    public Response<List<AppautoReservationView>> list(@RequestParam(required = false) Long deviceId,
                                                       @RequestParam(required = false) Long projectId,
                                                       @RequestParam(required = false) Long batchId,
                                                       @RequestParam(required = false,
                                                               defaultValue = "false")
                                                       boolean includeTerminal) {
        if (projectId == null) {
            throw new ServiceException("请先选择项目再查看设备预约（预约列表按项目隔离，"
                    + "不支持跨项目全量拉取）", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanView(UserContext.current(), projectId);
        return Response.builderSuccess(coreAppautoClient.listReservations(
                deviceId, projectId, batchId, includeTerminal));
    }

    
    @GetMapping("/calendar")
    @RequirePermission("perm:appauto:reservation:view")
    public Response<ReservationCalendarView> calendar(@RequestParam(required = false) Long projectId,
                                                      @RequestParam(required = false)
                                                              @DateTimeFormat(iso =
                                                                      DateTimeFormat.ISO.DATE_TIME)
                                                              LocalDateTime from,
                                                      @RequestParam(required = false)
                                                              @DateTimeFormat(iso =
                                                                      DateTimeFormat.ISO.DATE_TIME)
                                                              LocalDateTime to) {
        if (projectId == null) {
            throw new ServiceException("请先选择项目再查看设备预约日历（日历按项目隔离，"
                    + "不支持跨项目全量拉取）", BizErrorCode.VALIDATION_ERROR);
        }
        guard.assertCanView(UserContext.current(), projectId);
        LocalDateTime windowFrom = from != null ? from : LocalDateTime.now().minusHours(1);
        LocalDateTime windowTo = to != null ? to : windowFrom.plusHours(25);
        return Response.builderSuccess(reservationAppService.calendar(
                projectId, windowFrom, windowTo));
    }

    
    @PostMapping("/{id}/bind-batch")
    @RequirePermission("perm:appauto:reservation:create")
    public Response<AppautoReservationView> bindBatch(@PathVariable long id,
                                                      @RequestParam(required = false) Long batchId) {
        applyViewGate(coreAppautoClient.findReservation(id));
        return Response.builderSuccess(coreAppautoClient.bindReservationBatch(id, batchId));
    }

    
    @PostMapping("/{id}/release")
    @RequirePermission("perm:appauto:reservation:create")
    public Response<AppautoReservationView> release(@PathVariable long id,
                                                    @RequestParam(required = false) String reason) {
        applyViewGate(coreAppautoClient.findReservation(id));
        return Response.builderSuccess(coreAppautoClient.releaseReservation(id, reason));
    }

    
    @PostMapping("/{id}/cancel")
    @RequirePermission("perm:appauto:reservation:create")
    public Response<AppautoReservationView> cancel(@PathVariable long id,
                                                   @RequestParam(required = false) String reason) {
        applyViewGate(coreAppautoClient.findReservation(id));
        return Response.builderSuccess(coreAppautoClient.cancelReservation(id, reason));
    }

    
    @PostMapping("/{id}/approve")
    @RequirePermission("perm:appauto:reservation:approve")
    public Response<AppautoReservationView> approve(@PathVariable long id) {
        applyManageGate(coreAppautoClient.findReservation(id));
        return Response.builderSuccess(coreAppautoClient.approveReservation(
                id, UserContext.currentUserId()));
    }

    
    @PostMapping("/{id}/reject")
    @RequirePermission("perm:appauto:reservation:approve")
    public Response<AppautoReservationView> reject(@PathVariable long id,
                                                   @RequestParam(required = false) String reason) {
        applyManageGate(coreAppautoClient.findReservation(id));
        return Response.builderSuccess(coreAppautoClient.rejectReservation(
                id, UserContext.currentUserId(), reason));
    }

    
    @PostMapping("/sweep")
    @RequirePermission("perm:appauto:reservation:approve")
    public Response<Integer> sweep() {
        return Response.builderSuccess(coreAppautoClient.sweepReservations());
    }

    
    private void applyViewGate(AppautoReservationView row) {
        if (row.projectId() != null) {
            guard.assertCanView(UserContext.current(), row.projectId());
        }
    }

    
    private void applyManageGate(AppautoReservationView row) {
        if (row.projectId() == null) {
            guard.assertSystemAdminOnly(UserContext.current(), "审批跨项目共享设备的预约");
        } else {
            guard.assertCanManage(UserContext.current(), row.projectId());
        }
    }
}
