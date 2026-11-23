package com.sixbean.testpike.admin.skill.api.controller;

import com.sixbean.testpike.admin.rbac.app.component.PublicEndpointRegistry;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.admin.skill.api.dto.ProjectMemoryCreateRequest;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * project-memory ledger K12 wiring test (02 §2.16 permission
 * column; discipline inherited from the skills-domain ruling
 * SkillDomainPermissionWiringTest: a key may only be mounted when its
 * miss-population ⊆ the current refusal population, otherwise register an
 * exemption — no bare gap).
 * <p>Rulings: ①the two read doors (list / recycle-bin) mount the new key
 * perm:aichat:project-memory — VIEW family, so every predefined template
 * holds it = the hit-set equals the current login-only allow set, zero drift
 * (the member gate stays in ProjectAccessGuard.assertCanView as the combined
 * guard, the S3 precedent). ②The four write doors (create / status / delete /
 * restore) currently allow ADMIN ∪ project lead (SkillAccessGuard.assertCanManage
 * reuse, no new guard): any non-VIEW key hit-set is ≠ that population
 * (EDIT/CREATE family would shrink the non-ADMIN lead = forbidden drift,
 * skills-domain exemption ④ precedent), so they ride registered exemptions in
 * PublicEndpointRegistry. ③Keyed read doors must never enter the exemption
 * face (two-way gate, prevents silent no-ops).
 */
class ProjectMemoryPermissionWiringTest {

    private static final String KEY = "perm:aichat:project-memory";

    private static String permOf(String methodName, Class<?>... params) {
        try {
            Method m = ProjectMemoryController.class.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("project-memory 桥签名漂移（测试先于实现核对方法名/参数）："
                    + e.getMessage(), e);
        }
    }

    @Test
    void catalogKeyRegisteredInViewFamilyForZeroDriftMount() {
        assertTrue(PermissionCatalog.exists(KEY), "T449 账本门键必须入目录（缺0=前端 tab 门控与挂载链无锚）");
        assertEquals(PermActionFamily.VIEW, PermissionCatalog.fromKey(KEY).family(),
                "键族必须=VIEW：非 VIEW 族挂读路径会缩 developer 成员合法读面=漂移禁挂");
    }

    @Test
    void readDoorsMountLedgerKeyWriteDoorsStayGuardOnly() {
        assertEquals(KEY, permOf("list", Long.class, String.class, Integer.class, int.class, int.class),
                "账本列表=挂键读路径（VIEW 恒放行+成员闸组合）");
        assertEquals(KEY, permOf("recycleBin", Long.class, int.class, int.class), "回收站列表=同读口径");
        assertNull(permOf("create", ProjectMemoryCreateRequest.class),
                "手动新增=管理面（负责人合法，挂键致缩=漂移禁挂）");
        assertNull(permOf("setStatus", long.class, Long.class, int.class), "启停=同上");
        assertNull(permOf("delete", long.class, Long.class), "删除入回收站=同上");
        assertNull(permOf("restore", long.class, Long.class), "回收站还原=同上");
    }

    @Test
    void unkeyedWriteDoorsAreRegisteredExemptionsNotBareGaps() {
        String[][] exempt = {
                {"POST", "/api/v1/project-memory"},
                {"PUT", "/api/v1/project-memory/12/status"},
                {"DELETE", "/api/v1/project-memory/12"},
                {"POST", "/api/v1/project-memory/12/restore"},
        };
        for (String[] ep : exempt) {
            assertTrue(PublicEndpointRegistry.isPublic(ep[0], ep[1]),
                    "写路径未登记豁免（裸缺口）：" + ep[0] + " " + ep[1]);
        }
    }

    @Test
    void keyedReadDoorsMustNotEnterExemptionFace() {
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/project-memory"),
                "挂键端点禁混入豁免面");
        assertFalse(PublicEndpointRegistry.isPublic("GET", "/api/v1/project-memory/recycle-bin"),
                "挂键端点禁混入豁免面");
    }
}
