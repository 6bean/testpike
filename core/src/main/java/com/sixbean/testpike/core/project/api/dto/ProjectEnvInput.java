package com.sixbean.testpike.core.project.api.dto;


public record ProjectEnvInput(String name, int envType, String baseUrl, String configJson,
                              String endTypes, String browsers, String versionSnapshot) {
}
