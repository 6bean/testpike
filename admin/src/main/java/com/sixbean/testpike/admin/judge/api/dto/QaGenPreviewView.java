package com.sixbean.testpike.admin.judge.api.dto;

import java.util.List;
import java.util.Map;


public record QaGenPreviewView(List<Map<String, Object>> items, int chunkCount,
                               int promptTokens, int completionTokens, String model) {
}
