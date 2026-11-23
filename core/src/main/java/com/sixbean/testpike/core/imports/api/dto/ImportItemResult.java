package com.sixbean.testpike.core.imports.api.dto;


public record ImportItemResult(int rowNumber, boolean success, String reason, Long entityId) {
}
