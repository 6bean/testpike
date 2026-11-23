package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Four-color classification of one leaf difference between two JSON responses
 * (admin mirror of core ResponseDiffKind; the kernel owns the vocabulary and
 * the front-end maps these onto the four swatches).
 */
public enum ResponseDiffKind {
    ADDED,
    REMOVED,
    TYPE_CHANGED,
    VALUE_CHANGED
}
