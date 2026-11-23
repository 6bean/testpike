package com.sixbean.testpike.core.appauto.api.dto;


public record AppautoAppInput(
        String name,
        String packageName,
        String version,
        String platformCode,
        String permissionRuleRef) {
}
