package com.sixbean.testpike.api.notify.infrastructure.remote.client;


public interface NotificationReadClient {

    
    void markRead(long notificationId, long userId);
}
