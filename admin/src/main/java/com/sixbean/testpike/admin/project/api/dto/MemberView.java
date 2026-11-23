package com.sixbean.testpike.admin.project.api.dto;

import java.util.List;


public record MemberView(Long userId, String username, String nickName, int role, String roleLabel,
                         List<String> roles) {
}
