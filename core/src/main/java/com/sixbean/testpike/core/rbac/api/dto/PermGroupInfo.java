package com.sixbean.testpike.core.rbac.api.dto;

import java.util.List;


public record PermGroupInfo(String domain, String domainLabel, List<PermItemInfo> items) {
}
