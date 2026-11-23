package com.sixbean.testpike.admin.rbac.api.dto;

/**
 *
 * @param effect 1=allow / 2=deny
 */
public record RolePermOverrideItem(String permKey, int effect) {
}
