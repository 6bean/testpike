package com.sixbean.testpike.core.notification.domain.component;


public record NotificationEventFlags(boolean forced, boolean defaultOff) {

 
    public boolean defaultEnabled() {
        return !defaultOff;
    }
}
