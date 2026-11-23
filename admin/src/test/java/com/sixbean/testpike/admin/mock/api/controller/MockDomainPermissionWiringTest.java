package com.sixbean.testpike.admin.mock.api.controller;

import com.sixbean.testpike.admin.mock.api.dto.MockScenarioCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceCreateRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpacePolicyRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockSpaceTransitionRequest;
import com.sixbean.testpike.admin.mock.api.dto.MockStubCreateRequest;
import com.sixbean.testpike.admin.rbac.app.component.RequirePermission;
import com.sixbean.testpike.common.rbac.PermActionFamily;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Mock domain K12 permission wiring test ( pack C; discipline precedent:
 * tags/apitest domain wiring).
 * <p>Asserted surface: ①every mock controller lane carries its catalog key
 * (reflection signature stays in sync with the controllers — signature drift =
 * test fails first); ②family placement gives the intended audience split:
 * read=VIEW and write=EDIT hit developer and up (write starts at case-edit
 * parity per the AG ruling), publish=APPROVE excludes developer/tester and hits
 * project manager/test lead/admin (publish stays a management act), traffic=RUN
 * hits the executing roles; ③all four keys resolve in the catalog under domain
 * token "mock" (key shape perm:mock:{action} frozen by the ruling).
 */
class MockDomainPermissionWiringTest {

    private static String permOf(Class<?> controller, String methodName, Class<?>... params) {
        try {
            Method m = controller.getMethod(methodName, params);
            RequirePermission ann = m.getAnnotation(RequirePermission.class);
            return ann == null ? null : ann.value();
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(
                    "mock 域签名漂移（测试先于实现核对方法名/参数）：" + e.getMessage(), e);
        }
    }

    @Test
    void mockControllerLanesCarryTheirCatalogKeys() {
        assertEquals("perm:mock:read", permOf(MockSpaceController.class, "list", long.class),
                "空间列表=查看键");
        assertEquals("perm:mock:write", permOf(MockSpaceController.class, "create",
                        MockSpaceCreateRequest.class),
                "空间创建=编辑键（写闸与用例编辑同权起步，AG 裁决）");
        assertEquals("perm:mock:read", permOf(MockStubController.class, "list", long.class),
                "规则列表=查看键");
        assertEquals("perm:mock:write", permOf(MockStubController.class, "create",
                        long.class, MockStubCreateRequest.class),
                "规则创建=编辑键");
        // Hit-ledger browsing is a traffic-observation act: it carries the RUN-family
        // key (perm:mock:traffic), NOT read — inspecting live mocked traffic belongs to
        // the executing roles per the AG ruling.
        // The hit ledger lane carries a server-side sort pair (sortBy/sortDir) on
        // top of the paging window — pin the full current signature.
        assertEquals("perm:mock:traffic", permOf(MockHitController.class, "list",
                        long.class, Boolean.class, int.class, int.class,
                        String.class, String.class),
                "命中流水列表=流量键（RUN 族，AG 裁决）");
        // Scenario declarations are rule-plane configuration: browsing reads the
        // VIEW key, filing a machine writes the EDIT key (same audience split as
        // the stub lanes — keeps the two-face contract of the ruling).
        assertEquals("perm:mock:read", permOf(MockScenarioController.class, "list", long.class),
                "场景列表=查看键");
        assertEquals("perm:mock:write", permOf(MockScenarioController.class, "create",
                        long.class, MockScenarioCreateRequest.class),
                "场景创建=编辑键");
        // paged windows for the admin plane (display-scale iron law).
        // They browse rule-plane configuration, so they carry the VIEW key — zero
        // new permission keys (reuse confirmed by live scan of the mock catalog).
        assertEquals("perm:mock:read", permOf(MockScenarioController.class, "page",
                        long.class, int.class, int.class, String.class, String.class),
                "scenario paged window carries the read key");
        assertEquals("perm:mock:read", permOf(MockStubController.class, "page",
                        long.class, int.class, int.class),
                "stub paged window carries the read key");
        // Publish/retire is the APPROVE-family management act : the
        // bridge door consumes perm:mock:publish, registered since pack C.
        assertEquals("perm:mock:publish", permOf(MockSpaceController.class, "transition",
                        long.class, MockSpaceTransitionRequest.class),
                "空间发布/退役=发布键（APPROVE 族，AG 裁决归项目管理者起）");
        // saving the miss ladder is space configuration (same
        // EDIT family as create) — zero new permission keys (reuse confirmed by
        // live scan of the mock catalog).
        assertEquals("perm:mock:write", permOf(MockSpaceController.class, "updatePolicy",
                        long.class, MockSpacePolicyRequest.class),
                "未命中策略保存=编辑键（配置动作，与空间创建同族）");
    }

    @Test
    void fourMockKeysLiveUnderMockDomainWithExpectedFamilies() {
        assertEquals(PermActionFamily.VIEW, PermissionCatalog.fromKey("perm:mock:read").family());
        assertEquals(PermActionFamily.EDIT, PermissionCatalog.fromKey("perm:mock:write").family());
        assertEquals(PermActionFamily.APPROVE, PermissionCatalog.fromKey("perm:mock:publish").family());
        assertEquals(PermActionFamily.RUN, PermissionCatalog.fromKey("perm:mock:traffic").family());
        assertEquals("mock", PermissionCatalog.fromKey("perm:mock:read").domain().token(),
                "键中段=域 token（域一致性纪律）");
    }

    @Test
    void audienceSplitMatchesAgRuling() {
        Set<String> developer = RoleTemplate.DEVELOPER.baselineKeys();
        Set<String> tester = RoleTemplate.TESTER.baselineKeys();
        Set<String> pm = RoleTemplate.PROJECT_MANAGER.baselineKeys();
        Set<String> admin = RoleTemplate.ADMIN.baselineKeys();

        assertTrue(developer.contains("perm:mock:read") && developer.contains("perm:mock:write"),
                "开发人员=查看+编辑（写闸同权起步）");
        assertTrue(!developer.contains("perm:mock:publish"),
                "发布/退役不入开发模板（管理动作，AG 裁决归项目管理者起）");
        assertTrue(pm.contains("perm:mock:publish"), "项目经理=APPROVE 族命中发布键");
        assertTrue(tester.contains("perm:mock:traffic"), "测试人员=RUN 族命中流量归属键");
        assertTrue(admin.contains("perm:mock:read") && admin.contains("perm:mock:write")
                        && admin.contains("perm:mock:publish") && admin.contains("perm:mock:traffic"),
                "管理员=全目录展开四键齐命中");
    }
}
