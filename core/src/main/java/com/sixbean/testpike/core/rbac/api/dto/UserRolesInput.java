package com.sixbean.testpike.core.rbac.api.dto;

import java.util.List;


public record UserRolesInput(List<Long> roleIds) {
}
