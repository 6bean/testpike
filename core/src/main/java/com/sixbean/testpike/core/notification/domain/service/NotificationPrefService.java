package com.sixbean.testpike.core.notification.domain.service;

import com.sixbean.testpike.core.notification.api.dto.NotificationPrefItem;
import com.sixbean.testpike.core.notification.api.dto.NotificationPrefToggle;

import java.util.List;


public interface NotificationPrefService {

    
    List<NotificationPrefItem> listPrefs(long userId);

    
    void updatePrefs(long userId, List<NotificationPrefToggle> toggles);

    
    boolean shouldGenerate(long userId, String eventType);
}
