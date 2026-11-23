package com.sixbean.testpike.core.rbac.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.rbac.api.dto.PermInfo;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class RoleServiceTest {

    @Autowired
    private RoleService roleService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** R37 watermark ids: rows appearing above these belong to the running test. */
    private long auditFloor = Long.MAX_VALUE;
    private long roleFloor = Long.MAX_VALUE;
    private long userFloor = Long.MAX_VALUE;
    private long permFloor = Long.MAX_VALUE;

    @BeforeEach
    void setUp() {
        healFixtureLeftovers();
        purgeRunRows();
        seedBuiltinRoles();
        captureFloors();
    }

    @AfterEach
    void restore() {
        // Regression guard: purge only rows this suite created (builtin roles
        // and the seeded admin are never touched), then replay the canonical
        // builtin values so suites relying on tp_role see factory state.
        purgeRunRows();
        seedBuiltinRoles();
    }

    private void captureFloors() {
        auditFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_audit_log");
        roleFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_role");
        userFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_user");
        permFloor = SharedDbFixtureSweep.maxId(jdbcTemplate, "tp_role_permission");
    }

    /**
     * Same-name self-heal for crashed runs (the CaseDataScope precedent):
     * fixed fixture identities under the watermarks would otherwise collide
     * with this run's unique-key creates. Only this suite's literal names
     * and codes are touched; builtin roles and the seeded admin never are.
     */
    private void healFixtureLeftovers() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "k12_%");
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, "scope_%");
        SharedDbFixtureSweep.usersDown(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE username IN "
                        + "('ref_user','null_role_user','all_null_user','perm_user','admin_user',"
                        + "'multi_scope','multi_role_user','legacy_user','no_role_user')", Long.class));
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_role", jdbcTemplate.queryForList(
                "SELECT id FROM tp_role WHERE builtin = 0 AND code IN "
                        + "('auditor','dup','temp','temp2','config_mgr','skill_auditor',"
                        + "'k12_allow_role','k12_deny_role','legacy_func_role','legacy_map_role',"
                        + "'k12_dirty_role','k12_save_role','k12_effect_role','k12_conflict_role',"
                        + "'k12_overflow_role','k12_audit_role')", Long.class));
    }

    /**
     * Fixture-scoped teardown replacing the old blanket table wipe: the GUC
     * channel removes this run's audit rows, permission overrides go by id
     * watermark, custom roles unwind with their FK children, and fixture
     * users leave through the shared usersDown single source.
     */
    private void purgeRunRows() {
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_audit_log", jdbcTemplate.queryForList(
                "SELECT id FROM tp_audit_log WHERE id > ? ORDER BY id", Long.class, auditFloor));
        SharedDbFixtureSweep.deleteSince(jdbcTemplate, "tp_role_permission", permFloor);
        SharedDbFixtureSweep.byIdTree(jdbcTemplate, "tp_role", jdbcTemplate.queryForList(
                "SELECT id FROM tp_role WHERE id > ? ORDER BY id", Long.class, roleFloor));
        SharedDbFixtureSweep.usersDown(jdbcTemplate, jdbcTemplate.queryForList(
                "SELECT id FROM tp_user WHERE id > ? ORDER BY id", Long.class, userFloor));
    }

    
    private void seedBuiltinRoles() {
        // Upsert restores the canonical values too: the rename test edits the
        // tester row, and without TRUNCATE that change must be wound back here.
        jdbcTemplate.execute("""
                INSERT INTO tp_role (name, code, description, data_scope, status, builtin) VALUES
                    ('测试人员', 'tester', '执行测试用例、提交缺陷', 3, 1, 1),
                    ('测试负责人', 'test_lead', '管理测试计划、分配用例、评审', 2, 1, 1),
                    ('开发人员', 'developer', '查看用例与缺陷（只读）', 2, 1, 1),
                    ('系统管理员', 'admin', '系统配置、用户与角色管理', 1, 1, 1)
                ON CONFLICT (code) DO UPDATE SET
                    name = EXCLUDED.name, description = EXCLUDED.description,
                    data_scope = EXCLUDED.data_scope, status = EXCLUDED.status,
                    builtin = EXCLUDED.builtin
                """);
        jdbcTemplate.execute("""
                INSERT INTO tp_role_func (role_id, perm_code)
                SELECT r.id, p.perm FROM tp_role r
                JOIN (VALUES
                    ('admin', 'case:review'), ('admin', 'case:assign'), ('admin', 'config:manage'),
                    ('admin', 'llm:config'),  ('admin', 'user:manage'), ('admin', 'executor:view'),
                    ('test_lead', 'case:review'), ('test_lead', 'case:assign'), ('test_lead', 'executor:view'),
                    ('tester', 'executor:view'),
                    ('developer', 'executor:view')
                ) AS p(code, perm) ON p.code = r.code
                ON CONFLICT (role_id, perm_code) DO NOTHING
                """);
    }

    private long createUser(String username, int legacyRole) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO tp_user (username, password_hash, display_name, role, status)
                VALUES (?, 'x', ?, ?, 1) RETURNING id
                """, Long.class, username, username, legacyRole);
    }

    private RoleSaveInput saveInput(String name, String code, String description, int dataScope) {
        return new RoleSaveInput(name, code, description, dataScope, 1);
    }


    @Test
    void roles_返回4内置角色() {
        List<RoleInfo> roles = roleService.roles();
        List<RoleInfo> builtins = roles.stream().filter(r -> r.builtin() == 1)
                .collect(java.util.stream.Collectors.toList());
        assertEquals(4, builtins.size(), "内置 4 角色: " + builtins);
        Set<String> codes = builtins.stream().map(RoleInfo::code)
                .collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.containsAll(Set.of("tester", "test_lead", "developer", "admin")));
        RoleInfo admin = builtins.stream().filter(r -> r.code().equals("admin")).findFirst().orElseThrow();
        assertEquals(1, admin.dataScope(), "admin 数据范围=全部");
        assertEquals(1, admin.builtin(), "admin 内置");
    }

    @Test
    void create_新角色成功_字段回读一致() {
        long id = roleService.create(saveInput("安全审计员", "auditor", "审计日志查看", 2));
        RoleInfo info = roleService.get(id);
        assertEquals("安全审计员", info.name());
        assertEquals("auditor", info.code());
        assertEquals("审计日志查看", info.description());
        assertEquals(2, info.dataScope());
        assertEquals(1, info.status());
        assertEquals(0, info.builtin(), "自定义角色非内置");
        assertNotNull(info.createdAt());
    }

    @Test
    void create_code重复_409人话() {
        roleService.create(saveInput("重复编码", "dup", null, 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.create(saveInput("重复编码2", "dup", null, 2)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode());
        assertTrue(e.getMessage().contains("dup"), "人话提示含编码: " + e.getMessage());
    }

    @Test
    void get_不存在_404人话() {
        ServiceException e = assertThrows(ServiceException.class, () -> roleService.get(99999L));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
    }

    @Test
    void update_自定义角色_改名称与数据范围成功() {
        long id = roleService.create(saveInput("审计员", "auditor", null, 2));
        roleService.update(id, saveInput("高级审计员", "auditor", "含报表", 1));
        RoleInfo info = roleService.get(id);
        assertEquals("高级审计员", info.name());
        assertEquals("auditor", info.code(), "code 未变");
        assertEquals(1, info.dataScope(), "数据范围可改");
    }


    @Test
    void delete_内置角色_409人话() {
        long adminRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("admin")).findFirst().orElseThrow().id();
        ServiceException e = assertThrows(ServiceException.class, () -> roleService.delete(adminRoleId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "M4 删除保护：内置角色 40904");
        assertTrue(e.getMessage().contains("内置"), "人话提示内置角色不可删: " + e.getMessage());
    }

    @Test
    void update_内置角色改code_400人话() {
        long testerRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("tester")).findFirst().orElseThrow().id();
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.update(testerRoleId, saveInput("测试人员", "tester2", null, 3)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("内置"), "人话提示内置角色不可改编码: " + e.getMessage());
    }

    @Test
    void update_内置角色改名称_允许() {
        long testerRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("tester")).findFirst().orElseThrow().id();
        roleService.update(testerRoleId, saveInput("执行测试员", "tester", null, 3));
        assertEquals("执行测试员", roleService.get(testerRoleId).name());
    }

    @Test
    void delete_被用户引用角色_409人话() {
        long customId = roleService.create(saveInput("临时角色", "temp", null, 2));
        long userId = createUser("ref_user", 1);
        roleService.assignUserRoles(userId, List.of(customId));
        ServiceException e = assertThrows(ServiceException.class, () -> roleService.delete(customId));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, e.getCode(), "M4 删除保护：被引用 40904");
        assertTrue(e.getMessage().contains("分配"), "人话提示先解除分配: " + e.getMessage());
    }

    @Test
    void delete_未引用自定义角色_成功() {
        long customId = roleService.create(saveInput("临时角色", "temp", null, 2));
        roleService.delete(customId);
        ServiceException e = assertThrows(ServiceException.class, () -> roleService.get(customId));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode(), "删除后查不到");
    }

    @Test
    void assignUserRoles_roleIds含null_忽略null不抛NPE() {
        long customId = roleService.create(saveInput("临时角色", "temp2", null, 2));
        long userId = createUser("null_role_user", 1);
        roleService.assignUserRoles(userId, java.util.Arrays.asList(null, customId));
        List<RoleInfo> roles = roleService.rolesOfUser(userId);
        assertEquals(1, roles.size(), "null 元素被忽略，仅分配合法角色");
        assertEquals(customId, roles.get(0).id());
    }

    @Test
    void assignUserRoles_roleIds全null_清空角色() {
        long userId = createUser("all_null_user", 1);
        roleService.assignUserRoles(userId, java.util.Arrays.asList(null, null));
        assertEquals(0, roleService.rolesOfUser(userId).size(), "全 null 清空角色");
    }


    @Test
    void updateFuncs_配置成功_回读一致() {
        long customId = roleService.create(saveInput("审计员", "auditor", null, 2));
        roleService.updateFuncs(customId, List.of("executor:view", "case:review"));
        List<String> funcs = roleService.funcsOfRole(customId);
        assertEquals(2, funcs.size());
        assertTrue(funcs.containsAll(List.of("executor:view", "case:review")));
    }

    @Test
    void updateFuncs_非法权限点_400人话() {
        long customId = roleService.create(saveInput("审计员", "auditor", null, 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.updateFuncs(customId, List.of("not:exist")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("not:exist"), "指明非法权限点: " + e.getMessage());
    }

    @Test
    void permsOfUser_权限聚合_无权限不含_分配后含() {
        long customId = roleService.create(saveInput("配置管理员", "config_mgr", null, 2));
        long userId = createUser("perm_user", 1);
        roleService.assignUserRoles(userId, List.of(customId));

        assertFalse(roleService.permsOfUser(userId).contains("config:manage"), "未配置前无权限");

        roleService.updateFuncs(customId, List.of("config:manage"));
        assertTrue(roleService.permsOfUser(userId).contains("config:manage"), "配置后拥有权限");
    }

    @Test
    void permsOfUser_内置admin_全量权限兜底() {
        long adminRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("admin")).findFirst().orElseThrow().id();
        long userId = createUser("admin_user", 4);
        roleService.assignUserRoles(userId, List.of(adminRoleId));
        Set<String> perms = roleService.permsOfUser(userId);
        for (PermInfo p : roleService.permCatalog()) {
            assertTrue(perms.contains(p.code()), "admin 内置角色拥有全量权限: " + p.code());
        }
    }

    @Test
    void permCatalog_权限点目录完整() {
        Set<String> codes = roleService.permCatalog().stream()
                .map(PermInfo::code).collect(java.util.stream.Collectors.toSet());
        assertTrue(codes.containsAll(Set.of(
                "case:review", "case:assign", "config:manage", "llm:config",
                "user:manage", "executor:view", "role:manage")), "权限点全集: " + codes);
    }


    @Test
    void dataScopeOfUser_三种角色范围正确() {
        long testerRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("tester")).findFirst().orElseThrow().id();
        long leadRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("test_lead")).findFirst().orElseThrow().id();
        long adminRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("admin")).findFirst().orElseThrow().id();

        long tester = createUser("scope_tester", 1);
        long lead = createUser("scope_lead", 2);
        long admin = createUser("scope_admin", 4);
        roleService.assignUserRoles(tester, List.of(testerRoleId));
        roleService.assignUserRoles(lead, List.of(leadRoleId));
        roleService.assignUserRoles(admin, List.of(adminRoleId));

        assertEquals(3, roleService.dataScopeOfUser(tester), "测试人员=仅本人");
        assertEquals(2, roleService.dataScopeOfUser(lead), "测试负责人=项目内");
        assertEquals(1, roleService.dataScopeOfUser(admin), "系统管理员=全部");
    }

    @Test
    void dataScopeOfUser_多角色取最大可见范围() {
        long testerRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("tester")).findFirst().orElseThrow().id();
        long adminRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("admin")).findFirst().orElseThrow().id();
        long userId = createUser("multi_scope", 4);
        roleService.assignUserRoles(userId, List.of(adminRoleId, testerRoleId));
        assertEquals(1, roleService.dataScopeOfUser(userId), "admin+tester 并集=全部");
    }


    @Test
    void assignUserRoles_多角色分配_回读一致() {
        long leadRoleId = roleService.roles().stream()
                .filter(r -> r.code().equals("test_lead")).findFirst().orElseThrow().id();
        long customId = roleService.create(saveInput("审计员", "auditor", null, 2));
        long userId = createUser("multi_role_user", 2);

        roleService.assignUserRoles(userId, List.of(leadRoleId, customId));
        List<RoleInfo> roles = roleService.rolesOfUser(userId);
        assertEquals(2, roles.size());
        assertTrue(roles.stream().anyMatch(r -> r.code().equals("test_lead")));
        assertTrue(roles.stream().anyMatch(r -> r.code().equals("auditor")));

        roleService.assignUserRoles(userId, List.of(customId));
        List<RoleInfo> after = roleService.rolesOfUser(userId);
        assertEquals(1, after.size());
        assertEquals("auditor", after.get(0).code());
    }

    @Test
    void rolesOfUser_迁移兼容_无tp_user_role回退旧role字段() {
        long userId = createUser("legacy_user", 2);
        List<RoleInfo> roles = roleService.rolesOfUser(userId);
        assertEquals(1, roles.size());
        assertEquals("test_lead", roles.get(0).code(), "旧 role=2 映射 test_lead");
        assertTrue(roleService.permsOfUser(userId).contains("executor:view"),
                "迁移用户权限行为不变（test_lead 默认权限）");
    }

    @Test
    void rolesOfUser_未分配用户_返回空() {
        long userId = createUser("no_role_user", 0);
        assertTrue(roleService.rolesOfUser(userId).isEmpty(), "无角色关联返回空");
        assertEquals(2, roleService.dataScopeOfUser(userId), "无角色默认项目内");
    }


    private static final String APPROVE_KEY = "perm:skill:approve";
    private static final String BATCH_GEN_KEY = "perm:case:batch-generate";
    private static final String ROLE_MGR_KEY = "perm:system:role-manage";

    
    private void insertOverride(long roleId, String permKey, int effect) {
        jdbcTemplate.update("INSERT INTO tp_role_permission (role_id, perm_key, effect) VALUES (?, ?, ?)",
                roleId, permKey, effect);
    }

    private long roleIdOf(String code) {
        return roleService.roles().stream().filter(r -> r.code().equals(code))
                .findFirst().orElseThrow().id();
    }

    private long assignNewUser(long... roleIds) {
        long userId = createUser("k12_" + java.util.UUID.randomUUID().toString().substring(0, 8), 0);
        List<Long> ids = java.util.Arrays.stream(roleIds).boxed().toList();
        roleService.assignUserRoles(userId, ids);
        return userId;
    }

    @Test
    void permsOfUser_模板基线展开_test_lead含CREATE族不含MANAGE族() {
        long userId = assignNewUser(roleIdOf("test_lead"));
        Set<String> perms = roleService.permsOfUser(userId);
        assertTrue(perms.contains(BATCH_GEN_KEY), "test_lead 模板=+CREATE 族: " + BATCH_GEN_KEY);
        assertFalse(perms.contains(ROLE_MGR_KEY), "test_lead 无 MANAGE 族（模板基线边界）");
        assertFalse(perms.contains("*"), "禁止裸通配符（IT-K12-03）");
    }

    @Test
    void permsOfUser_自定义角色allow覆写_生效() {
        long customId = roleService.create(saveInput("技能审批员", "skill_auditor", null, 2));
        insertOverride(customId, APPROVE_KEY, 1); // effect=1 allow
        long userId = assignNewUser(customId);
        assertTrue(roleService.permsOfUser(userId).contains(APPROVE_KEY),
                "自定义角色=空基线+allow 覆写集");
    }

    @Test
    void permsOfUser_deny优先_压过模板基线() {
        long leadId = roleIdOf("test_lead");
        insertOverride(leadId, BATCH_GEN_KEY, 2);
        long userId = assignNewUser(leadId);
        assertFalse(roleService.permsOfUser(userId).contains(BATCH_GEN_KEY),
                "显式拒绝>模板默认（TC-K12-01 deny 优先）");
    }

    @Test
    void permsOfUser_跨角色allow与deny冲突_deny胜() {
        long customId = roleService.create(saveInput("冲突角色A", "k12_allow_role", null, 2));
        long denyRoleId = roleService.create(saveInput("冲突角色B", "k12_deny_role", null, 2));
        insertOverride(customId, APPROVE_KEY, 1);
        insertOverride(denyRoleId, APPROVE_KEY, 2);
        long userId = assignNewUser(customId, denyRoleId);
        assertFalse(roleService.permsOfUser(userId).contains(APPROVE_KEY),
                "同用户多角色：allow 与 deny 冲突=deny 胜（显式拒绝>显式允许）");
    }

    @Test
    void permsOfUser_legacy_tp_role_func既有行_视作allow直通() {
        long customId = roleService.create(saveInput("旧权限角色", "legacy_func_role", null, 2));
        roleService.updateFuncs(customId, List.of("case:review"));
        long userId = assignNewUser(customId);
        assertTrue(roleService.permsOfUser(userId).contains("case:review"),
                "tp_role_func 既有行=allow 直通（K4 行为不回归）");
    }

    @Test
    void permsOfUser_legacy四键_追加式投影新键形态_原形保留() {
        long customId = roleService.create(saveInput("映射角色", "legacy_map_role", null, 2));
        roleService.updateFuncs(customId, List.of("config:manage", "user:manage"));
        long userId = assignNewUser(customId);
        Set<String> perms = roleService.permsOfUser(userId);
        assertTrue(perms.contains("perm:system:system-config"), "config:manage 投影=K8 新键");
        assertTrue(perms.contains("perm:system:user-manage"), "user:manage 投影=K3 新键");
        assertTrue(perms.contains("config:manage") && perms.contains("user:manage"),
                "追加式：legacy 原形保留（过渡期前端消费，T206 收敛）");
        assertFalse(perms.contains("perm:system:storage-config"),
                "未收录键（executor:view 语义族）不得凭空新增——config:manage 只映射 system-config");
    }

    @Test
    void permsOfUser_admin角色_全量展开非裸星号() {
        long userId = assignNewUser(roleIdOf("admin"));
        Set<String> perms = roleService.permsOfUser(userId);
        assertTrue(perms.containsAll(com.sixbean.testpike.common.rbac.PermissionCatalog.allKeys()),
                "admin 全量=目录键全展开");
        assertFalse(perms.contains("*"), "禁止裸通配符混入裁决结果");
        assertTrue(perms.contains("config:manage"), "legacy 命名空间对 admin 兜底");
    }

    @Test
    void permsOfUser_覆写表未知键_40001点名() {
        long customId = roleService.create(saveInput("脏数据角色", "k12_dirty_role", null, 2));
        insertOverride(customId, "perm:bogus:unknown-key", 1);
        long userId = assignNewUser(customId);
        com.sixbean.testpike.common.exception.ValidationException e = assertThrows(
                com.sixbean.testpike.common.exception.ValidationException.class,
                () -> roleService.permsOfUser(userId));
        assertTrue(e.getMessage().contains("perm:bogus:unknown-key"), "报错点名非法键: " + e.getMessage());
        assertTrue(e.getMessage().contains("perm:"), "报错含合法前缀提示: " + e.getMessage());
    }


    private com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput permsInput(
            boolean confirm,
            com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideInput... items) {
        return new com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput(
                List.of(items), confirm);
    }

    private com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideInput perm(String key, int effect) {
        return new com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideInput(key, effect);
    }

    @Test
    void savePermissions_allowDeny保存_读回与裁决一致() {
        long leadId = roleIdOf("test_lead");
        roleService.savePermissions(leadId, permsInput(false,
                perm(APPROVE_KEY, 1), perm(BATCH_GEN_KEY, 2)));
        var saved = roleService.permissionsOfRole(leadId);
        assertEquals(2, saved.size(), "全量读回: " + saved);
        long userId = assignNewUser(leadId);
        Set<String> perms = roleService.permsOfUser(userId);
        assertTrue(perms.contains(APPROVE_KEY), "allow 覆写生效");
        assertFalse(perms.contains(BATCH_GEN_KEY), "deny 覆写生效（模板键被拒）");
    }

    @Test
    void savePermissions_未知键_40001点名含合法前缀() {
        long customId = roleService.create(saveInput("覆写角色", "k12_save_role", null, 2));
        com.sixbean.testpike.common.exception.ValidationException e = assertThrows(
                com.sixbean.testpike.common.exception.ValidationException.class,
                () -> roleService.savePermissions(customId,
                        permsInput(false, perm("perm:bogus:no-such", 1))));
        assertTrue(e.getMessage().contains("perm:bogus:no-such"), "点名非法键: " + e.getMessage());
        assertTrue(e.getMessage().contains("perm:"), "含合法前缀提示: " + e.getMessage());
    }

    @Test
    void savePermissions_非法effect_40001人话() {
        long customId = roleService.create(saveInput("覆写角色", "k12_effect_role", null, 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.savePermissions(customId, permsInput(false, perm(APPROVE_KEY, 3))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("1=allow") || e.getMessage().contains("2=deny"),
                "人话点明值域: " + e.getMessage());
    }

    @Test
    void savePermissions_空数组未confirm_40001人话_confirm后清空回模板() {
        long leadId = roleIdOf("test_lead");
        roleService.savePermissions(leadId, permsInput(false, perm(BATCH_GEN_KEY, 2)));
        long userId = assignNewUser(leadId);
        assertFalse(roleService.permsOfUser(userId).contains(BATCH_GEN_KEY), "先落 deny 覆写");

        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.savePermissions(leadId, permsInput(false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains("confirm"), "人话提示 confirm: " + e.getMessage());

        roleService.savePermissions(leadId, permsInput(true));
        assertEquals(0, roleService.permissionsOfRole(leadId).size(), "confirm=true 清空覆写");
        assertTrue(roleService.permsOfUser(userId).contains(BATCH_GEN_KEY),
                "清空后回模板基线（deny 消失，键恢复可读=裁决即时生效）");
    }

    @Test
    void savePermissions_同键allowDeny冲突_40001点名键() {
        long customId = roleService.create(saveInput("覆写角色", "k12_conflict_role", null, 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.savePermissions(customId, permsInput(false,
                        perm(APPROVE_KEY, 1), perm(APPROVE_KEY, 2))));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
        assertTrue(e.getMessage().contains(APPROVE_KEY), "点名冲突键: " + e.getMessage());
    }

    @Test
    void savePermissions_超长body_拒超目录总数() {
        long customId = roleService.create(saveInput("覆写角色", "k12_overflow_role", null, 2));
        var over = new java.util.ArrayList<com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideInput>();
        for (String key : com.sixbean.testpike.common.rbac.PermissionCatalog.allKeys()) {
            over.add(perm(key, 1));
        }
        over.add(perm(BATCH_GEN_KEY, 2));
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.savePermissions(customId,
                        new com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput(over, false)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, e.getCode());
    }

    @Test
    void savePermissions_审计beforeAfter全量() {
        long customId = roleService.create(saveInput("覆写角色", "k12_audit_role", null, 2));
        roleService.savePermissions(customId, permsInput(false, perm(ROLE_MGR_KEY, 1)));
        roleService.savePermissions(customId, permsInput(false,
                perm(APPROVE_KEY, 1), perm(BATCH_GEN_KEY, 2)));
        var rows = jdbcTemplate.queryForList("""
                SELECT before_json::text AS before_text, after_json::text AS after_text
                FROM tp_audit_log WHERE object_type = 'role' AND action = 'update_permissions'
                  AND object_id = ? ORDER BY id""", String.valueOf(customId));
        assertEquals(2, rows.size(), "两次保存两条审计");
        String before2 = String.valueOf(rows.get(1).get("before_text"));
        assertTrue(before2.contains(ROLE_MGR_KEY), "第二次 before=第一次保存后的旧覆写集全量: " + before2);
        String after1 = String.valueOf(rows.get(0).get("after_text"));
        assertTrue(after1.contains(ROLE_MGR_KEY), "第一次 after=新覆写集全量: " + after1);
        String after2 = String.valueOf(rows.get(1).get("after_text"));
        assertTrue(after2.contains(APPROVE_KEY) && after2.contains(BATCH_GEN_KEY),
                "第二次 after=新覆写集全量: " + after2);
    }

    @Test
    void permissionsOfRole_角色不存在_404() {
        ServiceException e = assertThrows(ServiceException.class,
                () -> roleService.permissionsOfRole(99999L));
        assertEquals(BizErrorCode.NOT_FOUND, e.getCode());
    }

    @Test
    void permCatalogGrouped_域分组目录全覆盖() {
        var groups = roleService.permCatalogGrouped();
        assertEquals(com.sixbean.testpike.common.rbac.PermissionDomain.values().length,
                groups.size(), "一级=全部功能域");
        long total = groups.stream().mapToLong(g -> g.items().size()).sum();
        assertEquals(com.sixbean.testpike.common.rbac.PermissionCatalog.allKeys().size(), total,
                "分组展开=目录全量");
        var skill = groups.stream().filter(g -> "skill".equals(g.domain())).findFirst().orElseThrow();
        assertEquals("SKILL能力", skill.domainLabel(), "域中文名（分组树第一层）");
        assertTrue(skill.items().stream().anyMatch(i -> APPROVE_KEY.equals(i.key())
                        && "SKILL 审批流".equals(i.label())),
                "键+中文名: " + skill.items());
    }
}
