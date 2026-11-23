package com.sixbean.testpike.admin.notification.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.notification.api.dto.NotificationView;
import com.sixbean.testpike.admin.notification.infrastructure.remote.client.CoreNotificationClient;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;


@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final CoreNotificationClient coreNotificationClient;

    public NotificationController(CoreNotificationClient coreNotificationClient) {
        this.coreNotificationClient = coreNotificationClient;
    }

 
    @RequirePermission("perm:ux:notify-center")
    @GetMapping
    public Response<PageView<NotificationView>> list(@RequestParam(required = false) Integer readFlag,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "20") int size,
                                                     @RequestParam(required = false) String eventType,
                                                     @RequestParam(required = false) String sortBy,
                                                     @RequestParam(required = false) String sortDir) {
        long userId = UserContext.current().userId();
        return Response.builderSuccess(coreNotificationClient.list(
                userId, readFlag, page, size, eventType, sortBy, sortDir));
    }

    
    @PutMapping("/{id}/read")
    public Response<Void> markRead(@PathVariable long id) {
        coreNotificationClient.markRead(id, UserContext.current().userId());
        return Response.builderSuccess(null);
    }

 
    @PutMapping("/read-all")
    public Response<Map<String, Integer>> markAllRead() {
        int affected = coreNotificationClient.markAllRead(UserContext.current().userId());
        return Response.builderSuccess(Map.of("affected", affected));
    }
}
