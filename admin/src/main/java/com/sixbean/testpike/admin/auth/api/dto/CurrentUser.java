package com.sixbean.testpike.admin.auth.api.dto;


import java.util.List;


public record CurrentUser(long userId, String username, String nickName, String deptName,
                          List<String> roles, long expiresAt, String avatar, String jti, Long pwdIat) {

 
    public CurrentUser(long userId, String username, String nickName, String deptName,
                       List<String> roles, long expiresAt, String avatar) {
        this(userId, username, nickName, deptName, roles, expiresAt, avatar, null, null);
    }

 
    public CurrentUser(long userId, String username, String nickName, String deptName,
                       List<String> roles, long expiresAt, String avatar, String jti) {
        this(userId, username, nickName, deptName, roles, expiresAt, avatar, jti, null);
    }
}
