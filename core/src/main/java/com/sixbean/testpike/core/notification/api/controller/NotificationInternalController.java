package com.sixbean.testpike.core.notification.api.controller;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;

import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;


@RestController
public class NotificationInternalController {

    private final NotificationService notificationService;
    private final AdminAlertRecipients adminAlertRecipients;

    public NotificationInternalController(NotificationService notificationService,
                                          AdminAlertRecipients adminAlertRecipients) {
        this.notificationService = notificationService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    
    @GetMapping("/internal/notifications/admin-recipients")
    public Response<List<Long>> adminRecipients() {
        return Response.builderSuccess(adminAlertRecipients.adminUserIds());
    }

    
    @PostMapping("/internal/notifications")
    public Response<Map<String, Long>> create(@RequestBody NotificationCreateInput input) {
        return Response.builderSuccess(Map.of("id", notificationService.create(input)));
    }

    
    @GetMapping("/internal/notifications")
    public Response<PageResult<NotificationInfo>> list(@RequestParam long userId,
                                                       @RequestParam(required = false) Integer readFlag,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int size,
                                                       @RequestParam(required = false) String eventType,
                                                       @RequestParam(required = false) String sortBy,
                                                       @RequestParam(required = false) String sortDir) {
        return Response.builderSuccess(notificationService.listByUser(
                userId, readFlag, page, size, eventType, sortBy, sortDir));
    }

    
    @PutMapping("/internal/notifications/{id}/read")
    public Response<Void> markRead(@PathVariable long id, @RequestParam long userId) {
        notificationService.markRead(id, userId);
        return Response.builderSuccess(null);
    }

    
    @PutMapping("/internal/notifications/read-all")
    public Response<Map<String, Integer>> markAllRead(@RequestParam long userId) {
        return Response.builderSuccess(Map.of("affected", notificationService.markAllRead(userId)));
    }
}
