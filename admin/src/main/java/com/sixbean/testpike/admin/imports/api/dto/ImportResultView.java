package com.sixbean.testpike.admin.imports.api.dto;

import java.util.List;


public record ImportResultView(int successCount, int failCount, List<ImportItemView> items) {
}
