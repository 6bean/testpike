package com.sixbean.testpike.core.notification.api.controller;

import com.sixbean.testpike.common.clientobject.Response;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefItem;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefToggle;
import com.sixbean.testpike.core.notification.domain.service.NotificationPrefService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;


@RestController
@RequestMapping("/internal/notification-prefs")
public class NotificationPrefInternalController {

    private final NotificationPrefService notificationPrefService;

    public NotificationPrefInternalController(NotificationPrefService notificationPrefService) {
        this.notificationPrefService = notificationPrefService;
    }

    
    @GetMapping
    public Response<List<NotificationPrefItem>> list(@RequestParam long userId) {
        return Response.builderSuccess(notificationPrefService.listPrefs(userId));
    }

    
    @PutMapping
    public Response<Void> update(@RequestParam long userId,
                                 @RequestBody List<NotificationPrefToggle> toggles) {
        notificationPrefService.updatePrefs(userId, toggles);
        return Response.builderSuccess(null);
    }
}
