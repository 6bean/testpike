package com.sixbean.testpike.admin.project.api.dto;

import java.time.OffsetDateTime;


public record ProjectTemplateView(Long id, String name, String industry,
                                  ProjectTemplateContentRequest content, Long createdBy,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
