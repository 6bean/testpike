package com.sixbean.testpike.admin.auth.app.component;

import com.sixbean.testpike.admin.auth.api.dto.CurrentUser;
import java.util.List;

/**
 * Single-source logged-in user dance for admin controller bridge suites (R40).
 *
 * <p>Why this file exists: five controller suites each re-spelled the identical
 * "put a TEST_LEAD member user into UserContext / clear it after each test"
 * block, and the duplication gate counted the ten-line window that also covers
 * the controller wiring as one group. The user-context dance now lives here —
 * suites keep their own stand-in wiring and row fixtures, behaviour is
 * byte-identical (the display name and role list are the same literals).
 */
public final class LoggedUserFixture {

    private LoggedUserFixture() {
    }

    /** Bind a TEST_LEAD member user (id=userId) as the current request user. */
    public static void loginAsTestLead(long userId) {
        UserContext.set(new CurrentUser(userId, "u" + userId, "U", "测试",
                List.of("TEST_LEAD"), System.currentTimeMillis() + 3600_000, null));
    }

    /** Drop the thread-bound user; call from @AfterEach. */
    public static void logout() {
        UserContext.clear();
    }
}
