package com.sixbean.testpike.core.appauto.api.dto;


public record TakeoverCreateInput(long deviceId, Long taskId, long requesterId, String reason) {
}
