package com.sixbean.testpike.admin.rbac.api.dto;

import java.util.List;


public record RolePermOverrideRequest(List<RolePermOverrideItem> items, boolean confirm) {
}
