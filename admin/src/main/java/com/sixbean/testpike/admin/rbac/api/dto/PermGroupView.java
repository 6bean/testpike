package com.sixbean.testpike.admin.rbac.api.dto;

import java.util.List;


public record PermGroupView(String domain, String domainLabel, List<PermItemView> items) {
}
