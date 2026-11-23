package com.sixbean.testpike.core.project.api.dto;


public record ProjectTemplateCreateInput(String name, String industry,
                                         ProjectTemplateContent content) {
}
