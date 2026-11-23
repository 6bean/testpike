package com.sixbean.testpike.api.notify.domain.component;


public record CallbackDecision(boolean allowed, String message, int retryAfterSeconds) {

    static CallbackDecision allow() {
        return new CallbackDecision(true, null, 0);
    }

    static CallbackDecision deny(String message, int retryAfterSeconds) {
        return new CallbackDecision(false, message, retryAfterSeconds);
    }
}
