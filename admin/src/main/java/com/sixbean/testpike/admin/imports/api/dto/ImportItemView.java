package com.sixbean.testpike.admin.imports.api.dto;


public record ImportItemView(int rowNumber, boolean success, String reason, Long entityId) {
}
