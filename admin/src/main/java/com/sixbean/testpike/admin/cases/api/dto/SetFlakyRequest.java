package com.sixbean.testpike.admin.cases.api.dto;


public record SetFlakyRequest(Boolean isolated, String reason) {
}
