package com.sixbean.testpike.admin.help.api.dto;


public record HelpDocView(
        long id,
        String domainCode,
        String title,
        String bodyMd,
        int sortNo,
        String version,
        String updatedAt) {
}
