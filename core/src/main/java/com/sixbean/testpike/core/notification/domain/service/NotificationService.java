package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.api.dto.NotificationInfo;


public interface NotificationService {

    
    long create(NotificationCreateInput input);

 
    boolean existsByDedupKey(String dedupKey);

    
    NotificationInfo get(long id);

    
    default PageResult<NotificationInfo> listByUser(long userId, Integer readFlag, int page, int size) {
        return listByUser(userId, readFlag, page, size, null);
    }

 
    default PageResult<NotificationInfo> listByUser(long userId, Integer readFlag, int page, int size,
                                                    String eventType) {
        return listByUser(userId, readFlag, page, size, eventType, null, null);
    }

    /**
 * Paged read with an optional whitelisted sort .
 * sortBy/sortDir null or blank keeps the historical newest-first order,
 * so every existing caller sees the untouched default without changes.
 */
    PageResult<NotificationInfo> listByUser(long userId, Integer readFlag, int page, int size,
                                            String eventType, String sortBy, String sortDir);

    
    void markRead(long id, long userId);

 
    int markAllRead(long userId);
}
