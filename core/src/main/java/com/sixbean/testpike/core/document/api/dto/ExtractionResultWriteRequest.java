package com.sixbean.testpike.core.document.api.dto;

import java.util.List;


public record ExtractionResultWriteRequest(List<ExtractionItem> items, String extractToken) {
}
