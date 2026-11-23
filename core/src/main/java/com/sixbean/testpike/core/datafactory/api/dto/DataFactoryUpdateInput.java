package com.sixbean.testpike.core.datafactory.api.dto;


public record DataFactoryUpdateInput(String name, String content, Integer status, String paramsJson) {
}
