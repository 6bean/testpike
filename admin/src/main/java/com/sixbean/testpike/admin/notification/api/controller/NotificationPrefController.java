package com.sixbean.testpike.admin.notification.api.controller;

import com.sixbean.testpike.admin.auth.app.component.UserContext;
import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefToggleRequest;
import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefView;
import com.sixbean.testpike.admin.notification.infrastructure.remote.client.CoreNotificationPrefClient;
import com.sixbean.testpike.common.clientobject.Response;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/api/v1/notification-prefs")
public class NotificationPrefController {

    private final CoreNotificationPrefClient coreNotificationPrefClient;

    public NotificationPrefController(CoreNotificationPrefClient coreNotificationPrefClient) {
        this.coreNotificationPrefClient = coreNotificationPrefClient;
    }

    
    @GetMapping
    public Response<List<NotificationPrefView>> list() {
        return Response.builderSuccess(coreNotificationPrefClient.list(UserContext.currentUserId()));
    }

    
    @PutMapping
    public Response<Void> update(@RequestBody List<NotificationPrefToggleRequest> toggles) {
        coreNotificationPrefClient.update(UserContext.currentUserId(), toggles);
        return Response.builderSuccess(null);
    }
}
