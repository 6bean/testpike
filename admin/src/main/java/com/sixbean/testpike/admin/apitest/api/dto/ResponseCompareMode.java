package com.sixbean.testpike.admin.apitest.api.dto;

/**
 * Format tri-state decided by core's read path before any comparison happens
 * (admin mirror of core ResponseCompareMode — field/enum names must match the
 * wire exactly, drift would silently drop values during deserialization).
 *
 * <p>JSON = four-color leaf diff entries; TEXT = line-level entries;
 * BINARY = SHA-256 digests only with an empty entry list.
 */
public enum ResponseCompareMode {
    JSON,
    TEXT,
    BINARY
}
