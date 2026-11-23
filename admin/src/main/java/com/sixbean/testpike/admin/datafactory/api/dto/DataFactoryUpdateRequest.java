package com.sixbean.testpike.admin.datafactory.api.dto;


public record DataFactoryUpdateRequest(String name, String content, Integer status, String paramsJson) {
}
