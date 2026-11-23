package com.sixbean.testpike.admin.user.infrastructure.remote.client;

import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.user.api.dto.UserCreateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserUpdateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserView;


import java.util.List;


public interface CoreUserClient {

    
    UserView authenticate(String username, String password);

    
    PageView<UserView> listUsers(String keyword, Integer role, Integer status, int page, int size,
                                 String sortBy, String sortDir);

    
    UserView getUser(long userId);

    
    long createUser(UserCreateRequest request);

    
    UserView updateUser(long userId, UserUpdateRequest request);

    
    UserView deleteUser(long userId);

    
    List<RoleView> roles();

    
    ImportResultView importUsers(byte[] file, String fileName);

    
    ImportResultView retryFailedUsers(byte[] file, String fileName, List<Integer> retryRows);

    
    byte[] userTemplate();

    
    UserView resetPassword(long userId, String newPassword);

    
    UserView updateProfile(long userId, String displayName, String avatar);

    
    UserView changePassword(long userId, String oldPassword, String newPassword);

 
    UserView unlock(long userId);

 
    com.sixbean.testpike.admin.user.api.dto.PasswordStatusView passwordStatus(long userId);
}
