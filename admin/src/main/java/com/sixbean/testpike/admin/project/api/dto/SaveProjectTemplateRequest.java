package com.sixbean.testpike.admin.project.api.dto;


public record SaveProjectTemplateRequest(String name, String industry,
                                         ProjectTemplateContentRequest content) {
}
