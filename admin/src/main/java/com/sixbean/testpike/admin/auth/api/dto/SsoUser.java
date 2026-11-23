package com.sixbean.testpike.admin.auth.api.dto;

import java.util.List;


public record SsoUser(long userId, String username, String nickName, String deptName,
                      List<String> roles, String email, String avatar, Long passwordChangedAt) {

 
    public SsoUser(long userId, String username, String nickName, String deptName,
                   List<String> roles, String email, String avatar) {
        this(userId, username, nickName, deptName, roles, email, avatar, null);
    }
}
