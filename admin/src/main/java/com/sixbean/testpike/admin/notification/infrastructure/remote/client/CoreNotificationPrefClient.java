package com.sixbean.testpike.admin.notification.infrastructure.remote.client;

import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefToggleRequest;
import com.sixbean.testpike.admin.notification.api.dto.NotificationPrefView;

import java.util.List;


public interface CoreNotificationPrefClient {

    
    List<NotificationPrefView> list(long userId);

    
    void update(long userId, List<NotificationPrefToggleRequest> toggles);
}
