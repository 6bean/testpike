package com.sixbean.testpike.core.rbac.api.dto;

import java.util.List;


public record RolePermissionsInput(List<RolePermOverrideInput> items, boolean confirm) {
}
