package com.sixbean.testpike.admin.project.api.dto;


public record EnvRequest(String name, int envType, String baseUrl, String configJson,
                         String endTypes, String browsers, String versionSnapshot) {
}
