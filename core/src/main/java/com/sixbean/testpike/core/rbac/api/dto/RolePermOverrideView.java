package com.sixbean.testpike.core.rbac.api.dto;

/**
 *
 * @param effect 1=allow / 2=deny
 */
public record RolePermOverrideView(String permKey, int effect) {
}
