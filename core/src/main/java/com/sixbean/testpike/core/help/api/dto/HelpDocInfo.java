package com.sixbean.testpike.core.help.api.dto;


public record HelpDocInfo(
        long id,
        String domainCode,
        String title,
        String bodyMd,
        int sortNo,
        String version,
        String updatedAt) {
}
