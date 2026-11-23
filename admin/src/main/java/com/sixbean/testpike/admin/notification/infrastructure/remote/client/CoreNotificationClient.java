package com.sixbean.testpike.admin.notification.infrastructure.remote.client;

import com.sixbean.testpike.admin.notification.api.dto.NotificationCreateRequest;
import com.sixbean.testpike.admin.notification.api.dto.NotificationView;
import com.sixbean.testpike.admin.project.api.dto.PageView;


public interface CoreNotificationClient {

 
    long create(NotificationCreateRequest request);

 
    default PageView<NotificationView> list(long userId, Integer readFlag, int page, int size,
                                            String eventType) {
        return list(userId, readFlag, page, size, eventType, null, null);
    }

    /** Same read plus the optional whitelisted sort pair (pass-through, no admin-side defaults). */
    PageView<NotificationView> list(long userId, Integer readFlag, int page, int size,
                                    String eventType, String sortBy, String sortDir);

    
    void markRead(long id, long userId);

 
    int markAllRead(long userId);
}
