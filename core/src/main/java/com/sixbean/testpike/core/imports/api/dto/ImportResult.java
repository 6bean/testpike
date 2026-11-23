package com.sixbean.testpike.core.imports.api.dto;

import java.util.List;


public record ImportResult(int successCount, int failCount, List<ImportItemResult> items) {
}
