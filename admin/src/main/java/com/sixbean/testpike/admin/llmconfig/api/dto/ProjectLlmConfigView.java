package com.sixbean.testpike.admin.llmconfig.api.dto;

import java.util.List;


public record ProjectLlmConfigView(Long llmConfigId, String llmConfigName,
                                   List<LlmOption> options) {
}
