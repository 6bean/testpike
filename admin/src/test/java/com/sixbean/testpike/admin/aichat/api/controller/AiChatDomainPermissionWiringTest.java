package com.sixbean.testpike.admin.aichat.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.PermissionDomain;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * AO batch dialog door wiring test ( closing pack; discipline precedent:
 * mock domain wiring).
 * <p>Asserted surface: ①both user-facing stream/abort lanes carry the catalog
 * key perm:aichat:use (reflection signature drift = test fails first); ②the
 * key sits in the catalog under domain token "aichat" with the VIEW family,
 * meaning every built-in member role carries it by template baseline — the key
 * opens the door only, isolation stays the core H1 project-guard single source
 * (a non-member streaming into someone else's session is still refused there);
 * ③no second aichat key appeared (the AO batch formalized exactly one door key
 * at this stage; further keys land with their consuming packs).
 */
class AiChatDomainPermissionWiringTest {

    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "aichat 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void streamAndAbortLanesCarryTheDialogDoorKey() {
        assertEquals("perm:aichat:use",
                permOf(AiChatStreamController.class, "stream", long.class,
                        com.sixbean.testpike.admin.aichat.api.dto.ChatStreamRequest.class,
                        jakarta.servlet.http.HttpServletResponse.class),
                "SSE stream lane must require perm:aichat:use");
        assertEquals("perm:aichat:use",
                permOf(AiChatStreamController.class, "abort", long.class),
                "abort lane must require perm:aichat:use");
        assertEquals("perm:aichat:use",
                permOf(AiChatStreamController.class, "inject", long.class,
                        com.sixbean.testpike.admin.aichat.api.dto.ChatInjectRequest.class),
                "mid-turn steering lane must require the same dialog door key");
    }

    @Test
    void catalogKeyShapeDomainAndFamilyAreFrozen() {
        PermissionCatalog entry = PermissionCatalog.fromKey("perm:aichat:use");
        assertNotNull(entry, "perm:aichat:use must be in the catalog (AO ruling key family)");
        assertEquals(PermissionDomain.AICHAT, entry.domain(),
                "domain grouping must be AICHAT");
        assertEquals("aichat", PermissionDomain.AICHAT.token(),
                "key domain segment must equal the domain token (single source)");
        assertEquals(PermActionFamily.VIEW, entry.family(),
                "VIEW family = every member role carries the door by template baseline");
        long aichatKeys = java.util.Arrays.stream(PermissionCatalog.values())
                .filter(p -> p.domain() == PermissionDomain.AICHAT).count();
        // Frozen AICHAT-domain set: dialog door (AO) + project-memory ledger read door (AP).
        // Adding a third key here requires an explicit ruling, not a silent catalog append.
        assertEquals(2, aichatKeys, "AICHAT domain carries exactly the dialog door and the project-memory ledger door");
        assertNotNull(PermissionCatalog.fromKey("perm:aichat:project-memory"),
                "project-memory ledger read key must stay in the AICHAT domain");
    }

    @Test
    void everyBuiltInRoleTemplateCarriesTheDoorKey() {
        for (RoleTemplate template : RoleTemplate.values()) {
            Set<String> baseline = template.baselineKeys();
            assertTrue(baseline.contains("perm:aichat:use"),
                    "member-visible dialog door must be in every role baseline: " + template.code());
        }
    }
}
