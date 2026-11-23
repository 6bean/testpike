package com.sixbean.testpike.admin.rbac.api.dto;

import java.util.List;


public record UserRolesRequest(List<Long> roleIds) {
}
