package com.sixbean.testpike.admin.auth.app.service;

import com.sixbean.testpike.admin.core.api.dto.UserProjectInfo;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.admin.auth.api.dto.LoginResult;
import com.sixbean.testpike.admin.auth.api.dto.MeResult;
import com.sixbean.testpike.admin.auth.app.component.JwtTokenService;
import com.sixbean.testpike.admin.auth.app.component.RotateOutcome;
import com.sixbean.testpike.admin.auth.app.component.SessionTokenService;
import com.sixbean.testpike.admin.auth.app.component.MockUserCatalog;
import com.sixbean.testpike.admin.auth.app.service.AuthService;
import com.sixbean.testpike.admin.auth.config.AuthProperties;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.ConfigValueClient;
import com.sixbean.testpike.admin.auth.infrastructure.remote.client.MockSsoClient;
import com.sixbean.testpike.admin.notification.api.dto.NotificationCreateRequest;
import com.sixbean.testpike.admin.program.api.dto.UserProgramView;
import com.sixbean.testpike.admin.program.infrastructure.remote.client.StubCoreProgramClient;
import com.sixbean.testpike.admin.notification.infrastructure.remote.client.CoreNotificationClient;
import com.sixbean.testpike.admin.core.infrastructure.remote.client.CoreUserProjectClient;
import com.sixbean.testpike.admin.imports.api.dto.ImportResultView;
import com.sixbean.testpike.admin.project.api.dto.PageView;
import com.sixbean.testpike.admin.rbac.api.dto.RoleView;
import com.sixbean.testpike.admin.user.api.dto.UserCreateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserUpdateRequest;
import com.sixbean.testpike.admin.user.api.dto.UserView;
import com.sixbean.testpike.admin.user.infrastructure.remote.client.CoreUserClient;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


class AuthServiceTest {

    private static final String SECRET = "test-secret-for-auth-service-test-0123456789";

    private final JwtTokenService jwt = new JwtTokenService(SECRET, 7200);
    private final TestCoreUserClient users = new TestCoreUserClient();

    private AuthService service(String mode) {
        return new AuthServiceImpl(
                new MockSsoClient("http://localhost:8081", new MockUserCatalog()),
                jwt, userProjects, users, new AuthProperties(mode),
                sessionTokens, configValues, notifications, passwordPolicy, programs);
    }

    /** MP2b boundary stand-in for the program lens (records the systemManager flag). */
    private final StubCoreProgramClient programs = new StubCoreProgramClient();

 
    private final TestCoreUserProjectClient userProjects = new TestCoreUserProjectClient();

 
    private final com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService passwordPolicy =
            mock(com.sixbean.testpike.admin.auth.app.component.PasswordPolicyService.class);

    
    private final SessionTokenService sessionTokens = mock(SessionTokenService.class);
    
    private final ConfigValueClient configValues = (key, def) -> def;
    private final CoreNotificationClient notifications = mock(CoreNotificationClient.class);

    
    private static final class TestCoreUserClient implements CoreUserClient {
        @Override
        public UserView authenticate(String username, String password) {
            if ("admin".equals(username) && "Admin@12345".equals(password)) {
                return new UserView(1L, "admin", "系统管理员", "admin@testpike.local", null,
                        "https://avatar.example.com/tester.png", 4, 1, 0, null, LocalDateTime.now(), LocalDateTime.now(),
                        0, null, null);
            }
            if ("tester01".equals(username) && "Tester@12345".equals(password)) {
                return new UserView(2L, "tester01", "测试小李", null, null,
                        null, 1, 1, 0, 1L, LocalDateTime.now(), LocalDateTime.now(),
                        0, null, null);
            }
            throw new ServiceException("用户名或密码错误", BizErrorCode.UNAUTHORIZED);
        }

        @Override
        public UserView updateProfile(long userId, String displayName, String avatar) {
            String nick = displayName == null || displayName.isBlank() ? "系统管理员" : displayName;
            return new UserView(userId, "admin", nick, "admin@testpike.local", null,
                    avatar, 4, 1, 0, null, LocalDateTime.now(), LocalDateTime.now(),
                    0, null, null);
        }

        @Override
        public UserView changePassword(long userId, String oldPassword, String newPassword) {
            if (!"Admin@12345".equals(oldPassword)) {
                throw new ServiceException("原密码不正确", BizErrorCode.VALIDATION_ERROR);
            }
            return new UserView(userId, "admin", "系统管理员", "admin@testpike.local", null,
                    null, 4, 1, 0, null, LocalDateTime.now(), LocalDateTime.now(),
                    0, null, null);
        }

        @Override
        public UserView unlock(long userId) {
            throw new UnsupportedOperationException("单测不触发用户解锁");
        }

        @Override
        public com.sixbean.testpike.admin.user.api.dto.PasswordStatusView passwordStatus(long userId) {
            throw new UnsupportedOperationException("单测不触发密码状态查询（R5 判定经 PasswordPolicyService 替身）");
        }

        @Override
        public PageView<UserView> listUsers(String keyword, Integer role, Integer status, int page, int size,
                                            String sortBy, String sortDir) {
            throw new UnsupportedOperationException("单测不触发用户管理");
        }

        @Override
        public UserView getUser(long userId) {
            if (userId == 1L) {
                return new UserView(1L, "admin", "系统管理员", "admin@testpike.local", null,
                        null, 4, 1, 0, null, LocalDateTime.now(), LocalDateTime.now(),
                        0, null, null);
            }
            if (userId == 2L) {
                return new UserView(2L, "tester01", "测试小李", null, null,
                        null, 1, 1, 0, 1L, LocalDateTime.now(), LocalDateTime.now(),
                        0, null, null);
            }
            throw new ServiceException("用户不存在", BizErrorCode.NOT_FOUND);
        }

        @Override
        public long createUser(UserCreateRequest request) {
            throw new UnsupportedOperationException("单测不触发用户管理");
        }

        @Override
        public UserView updateUser(long userId, UserUpdateRequest request) {
            throw new UnsupportedOperationException("单测不触发用户管理");
        }

        @Override
        public UserView deleteUser(long userId) {
            throw new UnsupportedOperationException("单测不触发用户管理");
        }

        @Override
        public UserView resetPassword(long userId, String newPassword) {
            throw new UnsupportedOperationException("单测不触发密码重置");
        }

        @Override
        public ImportResultView importUsers(byte[] file, String fileName) {
            throw new UnsupportedOperationException("单测不触发用户导入");
        }

        @Override
        public ImportResultView retryFailedUsers(byte[] file, String fileName, List<Integer> retryRows) {
            throw new UnsupportedOperationException("单测不触发用户导入");
        }

        @Override
        public byte[] userTemplate() {
            throw new UnsupportedOperationException("单测不触发用户模板");
        }

        @Override
        public List<RoleView> roles() {
            throw new UnsupportedOperationException("单测不触发用户管理");
        }
    }

    
    private static final class TestCoreUserProjectClient implements CoreUserProjectClient {
        private final java.util.Map<Long, Boolean> lastSystemManagerByUser = new java.util.HashMap<>();

        @Override
        public List<UserProjectInfo> listUserProjects(long userId, boolean systemManager) {
            lastSystemManagerByUser.put(userId, systemManager);
            if (systemManager) {
                return List.of(
                        new UserProjectInfo(10L, "商品中台", "PSHOP", 2, "项目负责人", "zh-CN"),
                        new UserProjectInfo(11L, "他人项目", "OTHER", 0, "系统角色可见", "zh-CN"));
            }
            return List.of(new UserProjectInfo(10L, "商品中台", "PSHOP", 2, "项目负责人", "zh-CN"));
        }
    }


    @Test
    void localLogin_管理员签发JWT且角色映射为ADMIN() {
        LoginResult result = service("local").localLogin("admin", "Admin@12345");
        assertNotNull(result.accessToken());
        assertEquals(7200, result.expiresIn());
        assertEquals(1L, result.user().userId());
        assertEquals(List.of("ADMIN"), result.user().roles(), "数字角色 4=系统管理员 → ADMIN");

        CurrentUser parsed = jwt.parse(result.accessToken());
        assertEquals(1L, parsed.userId());
        assertEquals("admin", parsed.username());
        assertEquals(List.of("ADMIN"), parsed.roles(), "JWT 载荷角色与 SSO 模式同语义");
    }

    @Test
    void localLogin_测试人员角色映射为TESTER() {
        LoginResult result = service("local").localLogin("tester01", "Tester@12345");
        assertEquals(List.of("TESTER"), result.user().roles(), "数字角色 1=测试人员 → TESTER");
    }

    @Test
    void localLogin_错误密码统一401提示() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("local").localLogin("admin", "wrong-password"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode());
        assertEquals("用户名或密码错误", ex.getMessage());
    }

    @Test
    void localLogin_未知用户统一401提示() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("local").localLogin("ghost", "AnyPass123"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode());
    }

    @Test
    void localLogin_sso模式禁用_不得回退本地() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("sso").localLogin("admin", "Admin@12345"));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "IT-AUTH-14：sso 模式 /auth/login 禁用（404 语义）");
        assertEquals("当前为 SSO 登录模式，请使用 SSO 登录", ex.getMessage());
    }


    @Test
    void login_本地模式禁用SSO() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("local").login("mock-tester"));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "local 模式 SSO 端点禁用（模式互斥防绕过）");
    }

    @Test
    void authorizeUrl_本地模式禁用() {
        assertThrows(ServiceException.class,
                () -> service("local").authorizeUrl("http://localhost:5173/sso-callback"));
    }

    @Test
    void login_sso模式返回JWT且载荷正确() {
        LoginResult result = service("sso").login("mock-tester");
        assertNotNull(result.accessToken());
        assertEquals(7200, result.expiresIn());
        assertEquals(1L, result.user().userId());
 assertEquals("Tester", result.user().nickName());

        CurrentUser parsed = jwt.parse(result.accessToken());
        assertEquals(1L, parsed.userId());
        assertEquals("tester", parsed.username());
    }

    @Test
    void sso模式_logout通知远端不抛错() {
        String token = service("sso").login("mock-tester01").accessToken();
        service("sso").logout(token, null);
    }


    @Test
    void me_返回用户与项目列表() {
        programs.userPrograms(List.of(
                new UserProgramView(7L, "Program-A", List.of(10L, 11L)),
                new UserProgramView(8L, "Program-B", List.of())));
        String token = service("local").localLogin("admin", "Admin@12345").accessToken();
        MeResult me = service("local").me(token);

        assertEquals(1L, me.user().userId());
        assertEquals(Boolean.TRUE, userProjects.lastSystemManagerByUser.get(1L),
                "ADMIN 会话应走可见域谱（systemManager=true）");
        assertEquals(2, me.projects().size(), "系统管理角色可见域=全部活跃项目（含非归属「系统角色可见」谱）");
        assertEquals("PSHOP", me.projects().get(0).projectCode());
        assertEquals("项目负责人", me.projects().get(0).roleLabel());
        assertEquals(0, me.projects().get(1).role(), "非归属项目 role=0 诚实标注");
        assertEquals("系统角色可见", me.projects().get(1).roleLabel());
        // MP2b: same flag drives the program lens; snapshot passes through untouched
        assertEquals(2, me.programs().size(), "me() must carry the program lens snapshot");
        assertEquals(7L, me.programs().get(0).programId());
        assertEquals(List.of(10L, 11L), me.programs().get(0).projectIds());
        assertEquals(List.of(), me.programs().get(1).projectIds(), "empty lens stays an empty list");
        assertEquals(Boolean.TRUE, programs.lastProgramsSystemManager(),
                "ADMIN session resolves systemManager=true once from a single source");
        assertEquals(1L, programs.lastProgramsUserId());
    }

 
    @Test
    void me_普通成员走成员谱_H1隔离不扩权() {
        String token = service("local").localLogin("tester01", "Tester@12345").accessToken();
        MeResult me = service("local").me(token);

        assertEquals(Boolean.FALSE, userProjects.lastSystemManagerByUser.get(2L),
                "TESTER 会话应走成员谱（systemManager=false）");
        assertEquals(1, me.projects().size(), "普通成员仅见本人归属项目（H1 隔离零放宽）");
        // MP2b: member scope forwards systemManager=false to the program lens as well
        assertEquals(Boolean.FALSE, programs.lastProgramsSystemManager());
        assertEquals(List.of(), me.programs(), "user without any lens gets an honest empty snapshot");
    }

    @Test
    void me_非法token被拒绝() {
        assertThrows(Exception.class, () -> service("local").me("bad-token"), "非法 token 查询用户信息必须拒绝");
        assertThrows(Exception.class, () -> service("local").me(""), "空 token 必须拒绝");
    }

    @Test
    void 本地模式_logout幂等不通知SSO() {
        String token = service("local").localLogin("admin", "Admin@12345").accessToken();
        service("local").logout(token, null);
        CurrentUser parsed = jwt.parse(token);
        assertEquals(1L, parsed.userId(), "无状态 JWT：登出由客户端丢弃 token，服务端幂等");
    }


 
    @Test
    void logout_R2双动作_拉黑jti并删refresh() {
        when(sessionTokens.issueRefresh(1L, 7)).thenReturn("R".repeat(43));
        LoginResult login = service("local").localLogin("admin", "Admin@12345");
        assertNotNull(login.refreshToken(), "本地登录应返回 refresh（R6）");
        assertEquals(43, login.refreshToken().length(), "refresh=256bit base64url 43 字符（D0）");
        verify(sessionTokens).issueRefresh(eq(1L), eq(7L));

        String jti = jwt.jtiOf(login.accessToken());
        assertNotNull(jti, "T119 起签发的 access 必带 jti（R2 锚点）");
        service("local").logout(login.accessToken(), login.refreshToken());
        verify(sessionTokens).revokeJti(eq(jti), anyLong());
        verify(sessionTokens).deleteRefresh(login.refreshToken());
    }

 
    @Test
    void logout_无凭证幂等() {
        service("local").logout(null, null);
        verify(sessionTokens, never()).revokeJti(any(), anyLong());
        verify(sessionTokens, never()).deleteRefresh(any());
    }

 
    @Test
    void sso登录不发refresh() {
        LoginResult result = service("sso").login("mock-tester");
        assertNull(result.refreshToken(), "SSO 模式 refreshToken 必须为 null");
        verify(sessionTokens, never()).issueRefresh(anyLong(), anyLong());
    }

 
    @Test
    void refresh_有效轮换返回新双token() {
        when(sessionTokens.rotateRefresh("valid-refresh", 7))
                .thenReturn(new RotateOutcome(1L, false));
        when(sessionTokens.issueRefresh(1L, 7)).thenReturn("new-refresh");
        LoginResult result = service("local").refresh("valid-refresh");
        assertNotNull(result.accessToken());
        assertEquals("new-refresh", result.refreshToken());
        verify(notifications, never()).create(any());
    }

 
    @Test
    void refresh_重放吊销全部会话并通知本人() {
        when(sessionTokens.rotateRefresh("replayed", 7))
                .thenReturn(new RotateOutcome(1L, true));
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("local").refresh("replayed"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode());
        assertTrue(ex.getMessage().contains("全部"), "重放提示人话说明已注销全部会话: " + ex.getMessage());
        var captor = org.mockito.ArgumentCaptor.forClass(NotificationCreateRequest.class);
        verify(notifications).create(captor.capture());
        assertEquals("LOGIN_SESSION_REVOKE", captor.getValue().eventType());
        assertEquals(1L, captor.getValue().userId());
    }

 
    @Test
    void refresh_存储层401与503透传() {
        when(sessionTokens.rotateRefresh("x", 7))
                .thenThrow(new ServiceException("会话已过期，请重新登录", BizErrorCode.UNAUTHORIZED));
        ServiceException ex = assertThrows(ServiceException.class, () -> service("local").refresh("x"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode());
        verify(sessionTokens, never()).revokeAllSessions(anyLong());

        when(sessionTokens.rotateRefresh("y", 7))
                .thenThrow(new ServiceException("系统繁忙，请稍后重试", BizErrorCode.SERVICE_UNAVAILABLE));
        ServiceException ex2 = assertThrows(ServiceException.class, () -> service("local").refresh("y"));
        assertEquals(BizErrorCode.SERVICE_UNAVAILABLE, ex2.getCode());
    }

 
    @Test
    void 改密后全端下线() {
        String token = service("local").localLogin("admin", "Admin@12345").accessToken();
        service("local").changePassword(token, "Admin@12345", "NewPass@98765");
        verify(sessionTokens).revokeAllSessions(1L);
    }

 
    @Test
    void sso模式_refresh禁用() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> service("sso").refresh("whatever"));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void authorizeUrl_生成mock回调() {
        String url = service("sso").authorizeUrl("http://localhost:5173/sso-callback");
        assertTrue(url.contains("code=mock-"), "授权 URL 应包含 mock code: " + url);
    }


 
    @Test
    void updateProfile_更新昵称头像_重签token载荷生效() {
        String token = service("local").localLogin("admin", "Admin@12345").accessToken();

        LoginResult result = service("local").updateProfile(token, "新昵称K5", "https://avatar.example.com/new.png");

        assertNotNull(result.accessToken());
        assertNotEquals(token, result.accessToken(), "资料更新后必须重签 token");
        assertEquals("新昵称K5", result.user().nickName());
        assertEquals("https://avatar.example.com/new.png", result.user().avatar());

        CurrentUser parsed = jwt.parse(result.accessToken());
        assertEquals("新昵称K5", parsed.nickName(), "重签 token 载荷含新昵称（reload 后仍一致）");
        assertEquals("https://avatar.example.com/new.png", parsed.avatar(), "重签 token 载荷含新头像");
    }

    
    @Test
    void updateProfile_非法token被拒绝() {
        assertThrows(Exception.class,
                () -> service("local").updateProfile("bad-token", "昵称", null));
    }

 
    @Test
    void changePassword_旧密码错误400透传_正确成功() {
        String token = service("local").localLogin("admin", "Admin@12345").accessToken();

        ServiceException wrong = assertThrows(ServiceException.class,
                () -> service("local").changePassword(token, "WrongOld!1", "NewPass!2b"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, wrong.getCode(), "旧密码错误 400（透传 core）");
        assertEquals("原密码不正确", wrong.getMessage());

        service("local").changePassword(token, "Admin@12345", "NewPass!2b");
    }
}
