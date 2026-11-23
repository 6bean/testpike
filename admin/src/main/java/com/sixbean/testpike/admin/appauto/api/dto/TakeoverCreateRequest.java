package com.sixbean.testpike.admin.appauto.api.dto;


public record TakeoverCreateRequest(long deviceId, Long taskId, String reason) {
}
