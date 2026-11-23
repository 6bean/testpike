package com.sixbean.testpike.admin.auth.app.service;

import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.api.dto.LoginResult;
import com.sixbean.testpike.admin.auth.api.dto.MeResult;
import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.app.component.JwtTokenService;
import com.sixbean.testpike.admin.auth.app.component.LocalRoleMapper;
import com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService;
import com.sixbean.testpike.admin.auth.app.component.RotateOutcome;
import com.sixbean.testpike.admin.auth.app.component.SessionTokenService;
import com.sixbean.testpike.admin.auth.config.AuthProperties;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.SsoClient;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
import com.sixbean.testpike.admin.notification.api.dto.NotificationCreateRequest;
import com.sixbean.testpike.admin.notification.infrastructure.remote.client.CoreNotificationClient;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;
import com.sixbean.testpike.admin.program.infrastructure.remote.client.CoreProgramClient;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.rbac.SystemManagerRoles;


import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;


@Service
public class AuthServiceImpl implements AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthServiceImpl.class);

    private static final String SSO_MODE_DISABLED_MSG = "当前为本地登录模式，请使用账号密码登录";
    private static final String LOCAL_MODE_DISABLED_MSG = "当前为 SSO 登录模式，请使用 SSO 登录";
 
    static final String KEY_ACCESS_TTL = "auth.access-ttl-seconds";
 
    static final String KEY_REFRESH_TTL_DAYS = "auth.refresh-ttl-days";
    private static final long DEFAULT_REFRESH_TTL_DAYS = 7;

    private final SsoClient ssoClient;
    private final JwtTokenService jwtTokenService;
    private final CoreUserProjectClient userProjectClient;
    private final CoreUserClient coreUserClient;
    private final AuthProperties authProperties;
    private final SessionTokenService sessionTokens;
    private final ConfigValueClient configValues;
    private final CoreNotificationClient notifications;
    private final PasswordPolicyService passwordPolicy;
    private final CoreProgramClient programClient;

    public AuthServiceImpl(SsoClient ssoClient, JwtTokenService jwtTokenService,
                       CoreUserProjectClient userProjectClient, CoreUserClient coreUserClient,
                       AuthProperties authProperties, SessionTokenService sessionTokens,
                       ConfigValueClient configValues, CoreNotificationClient notifications,
                       PasswordPolicyService passwordPolicy,
                       CoreProgramClient programClient) {
        this.ssoClient = ssoClient;
        this.jwtTokenService = jwtTokenService;
        this.userProjectClient = userProjectClient;
        this.coreUserClient = coreUserClient;
        this.authProperties = authProperties;
        this.sessionTokens = sessionTokens;
        this.configValues = configValues;
        this.notifications = notifications;
        this.passwordPolicy = passwordPolicy;
        this.programClient = programClient;
    }

    
    public LoginResult localLogin(String username, String password) {
        requireLocalMode();
        UserView info = coreUserClient.authenticate(username, password);
        SsoUser user = new SsoUser(info.id(), info.username(),
                info.displayName() == null ? info.username() : info.displayName(),
                null, LocalRoleMapper.toRoleNames(info.role()), info.email(), info.avatar(),
                toEpochSeconds(info.passwordChangedAt()));
        return issueSession(user, true);
    }

    
    public String authorizeUrl(String redirectUri) {
        requireSsoMode();
        return ssoClient.authorizeUrl(redirectUri);
    }

 
    public LoginResult login(String code) {
        requireSsoMode();
        SsoUser user = ssoClient.loginByCode(code);
        String token = jwtTokenService.issue(user, accessTtlSeconds());
        return new LoginResult(token, "Bearer", accessTtlSeconds(), user, null);
    }

 
    public void logout(String token, String refreshToken) {
        if (authProperties.isSso()) {
            ssoClient.logout(token);
            return;
        }
        if (token != null && !token.isBlank()) {
            try {
                CurrentUser user = jwtTokenService.parse(token);
                String jti = jwtTokenService.jtiOf(token);
                long remaining = user.expiresAt() / 1000 - System.currentTimeMillis() / 1000;
                sessionTokens.revokeJti(jti, remaining);
            } catch (ServiceException e) {
            }
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionTokens.deleteRefresh(refreshToken);
        }
    }

    
    public LoginResult refresh(String refreshToken) {
        requireLocalMode();
        long ttlDays = refreshTtlDays();
        RotateOutcome outcome = sessionTokens.rotateRefresh(refreshToken, ttlDays);
        UserView info = null;
        try {
            info = coreUserClient.getUser(outcome.userId());
        } catch (RuntimeException e) {
            log.warn("refresh 用户资料读取失败 userId={}（统一 401 重登）", outcome.userId(), e);
        }
        if (outcome.replayDetected()) {
            notifySessionRevoked(outcome.userId(),
                    info == null ? String.valueOf(outcome.userId()) : info.username());
            throw new ServiceException("检测到会话凭证异常复用，已注销全部登录会话，请重新登录",
                    BizErrorCode.UNAUTHORIZED);
        }
        if (info == null) {
            throw new ServiceException("会话已过期，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        SsoUser user = toSsoUser(info);
        String access = jwtTokenService.issue(user, accessTtlSeconds());
        String newRefresh = sessionTokens.issueRefresh(user.userId(), ttlDays);
        return new LoginResult(access, "Bearer", accessTtlSeconds(), user, newRefresh);
    }

    
    public MeResult me(String token) {
        CurrentUser user = jwtTokenService.parse(token);
        // One system-manager resolution feeds both lenses (same flag as ProjectAccessGuard, single source).
        boolean systemManager = SystemManagerRoles.isSystemManager(user.roles());
        List<UserProjectInfo> projects = userProjectClient.listUserProjects(user.userId(), systemManager);
        List<UserProgramView> programs = programClient.listProgramsForUser(user.userId(), systemManager);
        boolean expired = authProperties.isLocal() && user.pwdIat() != null
                && passwordPolicy.isExpired(user.userId());
        return new MeResult(user, projects, expired, programs);
    }

    
    public LoginResult updateProfile(String token, String displayName, String avatar) {
        CurrentUser current = jwtTokenService.parse(token);
        UserView updated = coreUserClient.updateProfile(current.userId(), displayName, avatar);
        Long pwdIat = updated.passwordChangedAt() != null
                ? toEpochSeconds(updated.passwordChangedAt()) : current.pwdIat();
        SsoUser user = new SsoUser(updated.id(), updated.username(),
                updated.displayName() == null ? updated.username() : updated.displayName(),
                current.deptName(), current.roles(), updated.email(), updated.avatar(), pwdIat);
        String newToken = jwtTokenService.issue(user, accessTtlSeconds());
        return new LoginResult(newToken, "Bearer", accessTtlSeconds(), user, null);
    }

    
    public void changePassword(String token, String oldPassword, String newPassword) {
        CurrentUser current = jwtTokenService.parse(token);
        coreUserClient.changePassword(current.userId(), oldPassword, newPassword);
        sessionTokens.revokeAllSessions(current.userId());
        passwordPolicy.evict(current.userId());
    }

    
    public CurrentUser requireValid(String token) {
        if (token == null || token.isBlank()) {
            throw new ServiceException("登录状态已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        return jwtTokenService.parse(token);
    }

    private void requireLocalMode() {
        if (!authProperties.isLocal()) {
            throw new ServiceException(LOCAL_MODE_DISABLED_MSG, BizErrorCode.NOT_FOUND);
        }
    }

    private void requireSsoMode() {
        if (!authProperties.isSso()) {
            throw new ServiceException(SSO_MODE_DISABLED_MSG, BizErrorCode.NOT_FOUND);
        }
    }

    
    private LoginResult issueSession(SsoUser user, boolean withRefresh) {
        long ttlDays = refreshTtlDays();
        String access = jwtTokenService.issue(user, accessTtlSeconds());
        String refresh = withRefresh ? sessionTokens.issueRefresh(user.userId(), ttlDays) : null;
        return new LoginResult(access, "Bearer", accessTtlSeconds(), user, refresh);
    }

 
    private long accessTtlSeconds() {
        return configPositive(KEY_ACCESS_TTL, jwtTokenService.ttlSeconds());
    }

    
    private long refreshTtlDays() {
        return configPositive(KEY_REFRESH_TTL_DAYS, DEFAULT_REFRESH_TTL_DAYS);
    }

    private long configPositive(String key, long fallback) {
        String raw = configValues.getValue(key, String.valueOf(fallback));
        try {
            long v = Long.parseLong(raw.trim());
            return v > 0 ? v : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    
    private SsoUser toSsoUser(UserView info) {
        return new SsoUser(info.id(), info.username(),
                info.displayName() == null ? info.username() : info.displayName(),
                null, LocalRoleMapper.toRoleNames(info.role()), info.email(), info.avatar(),
                toEpochSeconds(info.passwordChangedAt()));
    }

    
    private static Long toEpochSeconds(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.atZone(ZoneId.systemDefault()).toEpochSecond();
    }

 
    private void notifySessionRevoked(long userId, String username) {
        try {
            notifications.create(new NotificationCreateRequest(userId, null, "LOGIN_SESSION_REVOKE",
                    "登录会话已全部注销",
                    "检测到账号 " + username + " 的会话凭证异常复用（疑似令牌泄露），已注销全部登录会话并强制下线。如非本人操作请及时修改密码。",
                    "LOGIN_SESSION_REVOKE:" + userId + ":" + System.currentTimeMillis() / 60000));
        } catch (RuntimeException e) {
            log.warn("LOGIN_SESSION_REVOKE 站内信写入失败 userId={}（吊销已生效）", userId, e);
        }
    }
}
