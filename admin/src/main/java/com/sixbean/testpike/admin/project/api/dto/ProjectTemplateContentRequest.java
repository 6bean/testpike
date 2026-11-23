package com.sixbean.testpike.admin.project.api.dto;

import java.util.List;


public record ProjectTemplateContentRequest(List<Long> skillIds,
                                            List<TemplateTagItemRequest> tags,
                                            List<TemplateEnvItemRequest> envs,
                                            List<String> roleHints) {
}
