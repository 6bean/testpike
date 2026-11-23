package com.sixbean.testpike.core.project.api.dto;

import java.util.List;


public record MemberInfo(Long userId, String username, String nickName, int role, String roleLabel,
                         List<String> roles) {
}
