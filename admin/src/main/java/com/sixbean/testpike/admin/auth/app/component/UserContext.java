package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import com.sixbean.testpike.common.exception.BizErrorCode;


import com.sixbean.testpike.common.exception.ServiceException;


public final class UserContext {

    private static final ThreadLocal<CurrentUser> HOLDER = new ThreadLocal<>();

    private UserContext() {
    }

    public static void set(CurrentUser user) {
        HOLDER.set(user);
    }

    
    public static CurrentUser current() {
        return HOLDER.get();
    }

    
    public static long currentUserId() {
        CurrentUser user = HOLDER.get();
        if (user == null) {
            throw new ServiceException(
                    "登录状态已失效，请重新登录", BizErrorCode.UNAUTHORIZED);
        }
        return user.userId();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
