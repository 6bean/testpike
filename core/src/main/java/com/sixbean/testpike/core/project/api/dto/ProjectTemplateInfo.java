package com.sixbean.testpike.core.project.api.dto;

import java.time.OffsetDateTime;


public record ProjectTemplateInfo(Long id, String name, String industry,
                                  ProjectTemplateContent content, Long createdBy,
                                  OffsetDateTime createdAt, OffsetDateTime updatedAt) {
}
