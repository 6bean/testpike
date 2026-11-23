package com.sixbean.testpike.admin.auth.app.service;

import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;
import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.api.dto.LoginResult;
import com.sixbean.testpike.admin.auth.api.dto.MeResult;
import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.app.component.JwtTokenService;
import com.sixbean.testpike.admin.auth.app.component.LocalRoleMapper;
import com.sixbean.testpike.admin.auth.config.AuthProperties;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.SsoClient;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.springframework.stereotype.Service;
import java.util.List;


public interface AuthService {
    public LoginResult localLogin(String username, String password);

    public String authorizeUrl(String redirectUri);

    public LoginResult login(String code);

    
    public LoginResult refresh(String refreshToken);

    
    public void logout(String token, String refreshToken);

    public MeResult me(String token);

    public CurrentUser requireValid(String token);

    
    public LoginResult updateProfile(String token, String displayName, String avatar);

    
    public void changePassword(String token, String oldPassword, String newPassword);

}
