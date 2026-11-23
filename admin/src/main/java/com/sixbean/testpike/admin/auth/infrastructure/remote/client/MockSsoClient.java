package com.sixbean.testpike.admin.auth.infrastructure.remote.client;

import com.sixbean.testpike.admin.auth.api.dto.SsoUser;
import com.sixbean.testpike.admin.auth.app.component.MockUserCatalog;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.sixbean.testpike.common.exception.ServiceException;


public final class MockSsoClient implements SsoClient {

    private static final String CODE_PREFIX = "mock-";

    private final String frontendBase;
    private final MockUserCatalog catalog;

    public MockSsoClient(String frontendBase, MockUserCatalog catalog) {
        this.frontendBase = frontendBase;
        this.catalog = catalog;
    }

    @Override
    public String authorizeUrl(String redirectUri) {
        String target = (redirectUri == null || redirectUri.isBlank()) ? frontendBase : redirectUri;
        return target + (target.contains("?") ? "&" : "?") + "code=" + CODE_PREFIX
                + catalog.all().get(0).username();
    }

    @Override
    public SsoUser loginByCode(String code) {
        if (code == null || !code.startsWith(CODE_PREFIX)) {
            throw new ServiceException("无效的登录授权码，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        String username = code.substring(CODE_PREFIX.length());
        SsoUser user = catalog.findByUsername(username);
        if (user == null) {
            throw new ServiceException("登录用户不存在或已停用，请联系管理员", BizErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    @Override
    public void logout(String token) {
    }
}
