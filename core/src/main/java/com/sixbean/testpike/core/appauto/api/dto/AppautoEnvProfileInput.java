package com.sixbean.testpike.core.appauto.api.dto;

import java.util.Map;


public record AppautoEnvProfileInput(
        String name,
        String profileKind,
        Map<String, Object> payload,
        String remark,
        Long loginRecipeAssetId) {

    /** Shape used before the login-recipe binding existed (keeps every older caller compiling). */
    public AppautoEnvProfileInput(String name, String profileKind,
                                  Map<String, Object> payload, String remark) {
        this(name, profileKind, payload, remark, null);
    }
}
