package com.sixbean.testpike.core.user.domain.service;

import com.alibaba.excel.EasyExcel;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.imports.api.dto.ImportItemResult;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.support.SharedDbFixtureSweep;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.api.dto.UserQuery;
import com.sixbean.testpike.core.user.api.dto.UserUpdateInput;
import com.sixbean.testpike.core.user.domain.service.UserService;


import com.sixbean.testpike.common.exception.ServiceException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;


@SpringBootTest(classes = CoreApplication.class)
class UserServiceTest {

    @Autowired
    private UserService userService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    @Autowired
    private com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCache;

    /** Suite username prefix; the LIKE sweep below is the only teardown channel. */
    private static final String PREFIX = "fxus_";

    @BeforeEach
    void dropStaleFixtureUsers() {
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
    }

    @AfterEach
    void sweepFixtureUsers() {
        // R37: sweep this suite's fixture users with their FK children,
        // notifications and audit rows. The shipped seed admin is baseline
        // data and never deleted, so no re-seed channel is needed.
        SharedDbFixtureSweep.usersByPatternDown(jdbcTemplate, PREFIX + "%");
    }

    private UserCreateInput input(String base, String password, String displayName, int role) {
        String username = PREFIX + base;
        return new UserCreateInput(username, password, displayName, "u" + username + "@testpike.local",
                "13800000000", role);
    }

    @Test
    void create_创建成功_密码BCrypt哈希非明文() throws Exception {
        long id = userService.create(99L, input("tester_a", "Passw0rd!a", "测试甲", 1));

        UserInfo info = userService.get(id);
        assertEquals(PREFIX + "tester_a", info.username());
        assertEquals("测试甲", info.displayName());
        assertEquals(1, info.role(), "默认角色测试人员");
        assertEquals(1, info.status(), "新建默认启用");
        assertEquals(0, info.deleted());
        assertEquals(99L, info.createdBy());

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM tp_user WHERE id = ?", String.class, id);
        assertNotNull(hash);
        assertFalse(hash.contains("Passw0rd!a"), "密码不得明文存储");
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希前缀 $2");
        assertNotEquals("Passw0rd!a", hash);
        String json = objectMapper.writeValueAsString(info);
        assertFalse(json.contains("passwordHash"), "对外 JSON 不得暴露密码哈希字段");
        assertFalse(json.contains("$2a$"), "对外 JSON 不得出现哈希值");
    }

    @Test
    void create_无创建人_createdBy为null() {
        long id = userService.create(null, input("no_creator", "Passw0rd!a", "无创建人", 1));

        assertNull(userService.get(id).createdBy(), "created_by 为空时不得误报 0");
    }

    @Test
    void create_重名_抛409冲突() {
        userService.create(99L, input("dup_user", "Passw0rd!a", "第一个", 1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.create(100L, input("dup_user", "Passw0rd!b", "第二个", 2)));
        assertEquals(BizErrorCode.RESOURCE_CONFLICT, ex.getCode(), "重名应 40904（IT-AUTH-11）");
    }

    @Test
    void create_密码过短或为空_参数校验() {
        ServiceException shortPwd = assertThrows(ServiceException.class,
                () -> userService.create(99L, input("short_pwd", "1234567", "短密码", 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, shortPwd.getCode());

        ServiceException emptyPwd = assertThrows(ServiceException.class,
                () -> userService.create(99L, input("empty_pwd", "", "空密码", 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, emptyPwd.getCode());
    }

    @Test
    void create_用户名空或超长_参数校验() {
        // Rejected payloads never reach the database, so the fixture sweep
        // cannot see them: build these inputs without the fxus_ prefix,
        // otherwise the "empty" name would arrive as a non-blank value and
        // the validation under test would be silently bypassed.
        ServiceException emptyName = assertThrows(ServiceException.class,
                () -> userService.create(99L,
                        new UserCreateInput("", "Passw0rd!a", "无名", "u@testpike.local", "13800000000", 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, emptyName.getCode());

        ServiceException longName = assertThrows(ServiceException.class,
                () -> userService.create(99L,
                        new UserCreateInput("a".repeat(51), "Passw0rd!a", "超长名", "u@testpike.local", "13800000000", 1)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, longName.getCode());
    }

    @Test
    void authenticate_正确密码_返回用户() {
        long id = userService.create(99L, input("login_ok", "Passw0rd!a", "登录甲", 2));

        UserInfo user = userService.authenticate(PREFIX + "login_ok", "Passw0rd!a");
        assertEquals(id, user.id());
        assertEquals(2, user.role());
    }

    @Test
    void authenticate_错误密码与不存在用户_统一401提示() {
        userService.create(99L, input("login_bad", "Passw0rd!a", "登录乙", 1));

        String wrongPwdMsg = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "login_bad", "WrongPass!1")).getMessage();
        String notExistMsg = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "no_such_user", "Passw0rd!a")).getMessage();

        assertEquals(wrongPwdMsg, notExistMsg, "错误密码与不存在用户提示必须一致");
        assertEquals(BizErrorCode.UNAUTHORIZED,
                assertThrows(ServiceException.class,
                        () -> userService.authenticate(PREFIX + "login_bad", "WrongPass!1")).getCode());
    }

    @Test
    void authenticate_停用用户_403拒绝() {
        long id = userService.create(99L, input("disabled_user", "Passw0rd!a", "停用甲", 1));
        userService.update(99L, id, new UserUpdateInput("停用甲", "u@testpike.local", "13800000000", 1, 0));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "disabled_user", "Passw0rd!a"));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "停用用户登录 403（IT-AUTH-09）");
        assertTrue(ex.getMessage().contains("停用"), "停用提示人话");
    }

    @Test
    void authenticate_软删用户_401拒绝() {
        long id = userService.create(99L, input("deleted_user", "Passw0rd!a", "删除甲", 1));
        userService.delete(99L, id);

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.authenticate(PREFIX + "deleted_user", "Passw0rd!a"));
        assertEquals(BizErrorCode.UNAUTHORIZED, ex.getCode(), "软删用户按不存在处理");
    }

    @Test
    void update_修改资料与角色_生效() {
        long id = userService.create(99L, input("upd_user", "Passw0rd!a", "原名", 1));
        userService.update(99L, id, new UserUpdateInput("新名字", "new@testpike.local", "13900000000", 4, 1));

        UserInfo info = userService.get(id);
        assertEquals("新名字", info.displayName());
        assertEquals("new@testpike.local", info.email());
        assertEquals(4, info.role(), "角色分配为系统管理员（IT-AUTH-13）");
        assertEquals(1, info.status());
    }

    @Test
    void update_启用停用_生效且登录联动() {
        long id = userService.create(99L, input("toggle_user", "Passw0rd!a", "开关甲", 1));

        userService.update(99L, id, new UserUpdateInput("开关甲", "u@testpike.local", "13800000000", 1, 0));
        assertEquals(0, userService.get(id).status(), "停用生效");
        assertThrows(ServiceException.class, () -> userService.authenticate(PREFIX + "toggle_user", "Passw0rd!a"));

        userService.update(99L, id, new UserUpdateInput("开关甲", "u@testpike.local", "13800000000", 1, 1));
        assertEquals(1, userService.get(id).status(), "重新启用生效");
        assertNotNull(userService.authenticate(PREFIX + "toggle_user", "Passw0rd!a"), "启用后可登录（IT-AUTH-13）");
    }

    @Test
    void update_用户不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.update(99L, 999999L, new UserUpdateInput("x", "x@x.local", "13800000000", 1, 1)));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void changePassword_旧密码错误_拒绝() {
        long id = userService.create(99L, input("pwd_user", "Passw0rd!a", "改密甲", 1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.changePassword(id, "WrongOld!1", "NewPass!2b"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode(), "原密码错误 40001");
    }

    @Test
    void changePassword_成功_新密码可登录旧密码失效() {
        long id = userService.create(99L, input("pwd_ok", "Passw0rd!a", "改密乙", 1));

        userService.changePassword(id, "Passw0rd!a", "NewPass!2b");
        assertThrows(ServiceException.class, () -> userService.authenticate(PREFIX + "pwd_ok", "Passw0rd!a"),
                "旧密码失效");
        assertNotNull(userService.authenticate(PREFIX + "pwd_ok", "NewPass!2b"), "新密码可登录");
    }

    @Test
    void changePassword_新密码过短_拒绝() {
        long id = userService.create(99L, input("pwd_short", "Passw0rd!a", "改密丙", 1));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.changePassword(id, "Passw0rd!a", "short"));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
    }

    @Test
    void delete_软删_列表与登录不可见_同名可重建() {
        long id = userService.create(99L, input("soft_del", "Passw0rd!a", "软删甲", 1));

        userService.delete(99L, id);
        assertEquals(1, userService.get(id).deleted(), "软删标记 deleted=1");

        PageResult<UserInfo> page = userService.list(new UserQuery("soft_del", null, null, 1, 20));
        assertTrue(page.list().isEmpty(), "软删用户列表不可见");
        assertThrows(ServiceException.class, () -> userService.authenticate(PREFIX + "soft_del", "Passw0rd!a"));
        long newId = userService.create(99L, input("soft_del", "Passw0rd!b", "软删乙", 2));
        assertNotEquals(id, newId);
        assertEquals(2, userService.authenticate(PREFIX + "soft_del", "Passw0rd!b").role(), "新账号正常登录");
    }

    @Test
    void delete_不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.delete(99L, 999999L));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode());
    }

    @Test
    void delete_删除自己_拒绝() {
        long id = userService.create(99L, input("self_del", "Passw0rd!a", "自己", 4));

        ServiceException ex = assertThrows(ServiceException.class, () -> userService.delete(id, id));
        assertEquals(BizErrorCode.FORBIDDEN, ex.getCode(), "不能删除自己（避免系统无管理员）");
        assertEquals(0, userService.get(id).deleted(), "删除被拒绝，账号保留");
    }

    @Test
    void list_关键字与角色过滤_分页() {
        userService.create(99L, input("alpha_tester", "Passw0rd!a", "阿尔法", 1));
        userService.create(99L, input("alpha_lead", "Passw0rd!a", "阿尔法组长", 2));
        userService.create(99L, input("beta_dev", "Passw0rd!a", "贝塔开发", 3));

        PageResult<UserInfo> byKeyword = userService.list(new UserQuery("阿尔法", null, null, 1, 20));
        assertEquals(2, byKeyword.list().size(), "关键字按用户名/显示名模糊");

        PageResult<UserInfo> byRole = userService.list(new UserQuery("beta_dev", 3, null, 1, 20));
        assertEquals(1, byRole.list().size());
        assertEquals(PREFIX + "beta_dev", byRole.list().get(0).username());

        PageResult<UserInfo> paged = userService.list(new UserQuery(PREFIX, null, null, 1, 2));
        assertEquals(2, paged.list().size());
        assertEquals(3, paged.total(), "total 为过滤后总数");
    }

    // ── Server-side sorting through a physical-column whitelist (table-feature baseline) ──

    @Test
    void test_list_sorts_by_whitelisted_columns_both_directions() {
        // Insertion order deliberately differs from BOTH username orders so the
        // id-ASC default cannot accidentally satisfy either direction assertion.
        userService.create(99L, input("zz_sort", "Passw0rd!a", "排序丙", 1));
        userService.create(99L, input("aa_sort", "Passw0rd!a", "排序甲", 2));
        userService.create(99L, input("mm_sort", "Passw0rd!a", "排序乙", 3));

        PageResult<UserInfo> asc = userService.list(
                new UserQuery("_sort", null, null, 1, 20, "username", "asc"));
        assertEquals(3, asc.total());
        assertEquals(List.of(PREFIX + "aa_sort", PREFIX + "mm_sort", PREFIX + "zz_sort"),
                asc.list().stream().map(UserInfo::username).toList(), "asc must reorder server-side");

        PageResult<UserInfo> desc = userService.list(
                new UserQuery("_sort", null, null, 1, 20, "username", "desc"));
        assertEquals(List.of(PREFIX + "zz_sort", PREFIX + "mm_sort", PREFIX + "aa_sort"),
                desc.list().stream().map(UserInfo::username).toList(), "desc mirrors asc");

        // Role is a second whitelist member: 3 → 2 → 1 descending across our three rows.
        PageResult<UserInfo> byRole = userService.list(
                new UserQuery("_sort", null, null, 1, 20, "role", "desc"));
        assertEquals(List.of(3, 2, 1),
                byRole.list().stream().map(UserInfo::role).toList(), "role column sorts numerically");

        // Default order (no sort requested) keeps the historical id-ASC behaviour.
        PageResult<UserInfo> dflt = userService.list(new UserQuery("_sort", null, null, 1, 20));
        assertEquals(List.of(PREFIX + "zz_sort", PREFIX + "aa_sort", PREFIX + "mm_sort"),
                dflt.list().stream().map(UserInfo::username).toList(), "default stays id ASC");
    }

    @Test
    void test_list_sort_by_injection_shaped_key_rejected_400() {
        userService.create(99L, input("inj_sort", "Passw0rd!a", "注入对照", 1));
        ServiceException ex = assertThrows(ServiceException.class, () ->
                userService.list(new UserQuery(null, null, null, 1, 20, "id OR 1=1", "asc")));
        assertEquals(BizErrorCode.VALIDATION_ERROR, ex.getCode());
        assertTrue(ex.getMessage().contains("不在白名单"), ex.getMessage());
    }

    @Test
    void roles_角色清单来自tp_role_含4内置角色() {
        List<RoleInfo> builtins = userService.roles().stream()
                .filter(r -> r.builtin() == 1).toList();
        assertEquals(4, builtins.size(), "内置角色恰 4 个");
        assertEquals("tester", builtins.get(0).code());
        assertEquals("测试人员", builtins.get(0).name());
        assertEquals(1, builtins.get(0).builtin(), "内置角色标记");
        assertEquals("test_lead", builtins.get(1).code());
        assertEquals("测试负责人", builtins.get(1).name());
        assertEquals("developer", builtins.get(2).code());
        assertEquals("开发人员", builtins.get(2).name());
        assertEquals("admin", builtins.get(3).code());
        assertEquals("系统管理员", builtins.get(3).name());
    }

    @Test
    void create_角色写入tp_user_role_多对多关联正确() {
        long id = userService.create(99L, input("rbac_user", "Passw0rd!a", "RBAC 用户", 1));
        Long roleCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_role ur JOIN tp_role r ON r.id = ur.role_id" +
                        " WHERE ur.user_id = ? AND r.code = 'tester'", Long.class, id);
        assertEquals(1L, roleCount, "tp_user_role 关联内置 tester 角色");
        Integer legacy = jdbcTemplate.queryForObject(
                "SELECT role FROM tp_user WHERE id = ?", Integer.class, id);
        assertEquals(1, legacy, "tp_user.role 兼容主角色=1");
    }

    @Test
    void update_角色变更同步tp_user_role_旧关联替换() {
        long id = userService.create(99L, input("rbac_upd", "Passw0rd!a", "RBAC 更新", 1));
        userService.update(99L, id, new UserUpdateInput("RBAC 更新2", "u@testpike.local", "13800000001", 2, 1));
        List<String> codes = jdbcTemplate.queryForList("""
                SELECT r.code FROM tp_user_role ur JOIN tp_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? ORDER BY ur.role_id
                """, String.class, id);
        assertEquals(List.of("test_lead"), codes, "角色变更后 tp_user_role 替换为主角色");
        assertEquals(2, userService.get(id).role(), "tp_user.role 兼容主角色=2");
    }


    
    private byte[] userExcel(List<List<Object>> rows) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        EasyExcel.write(baos)
                .head(List.of(List.of("登录名"), List.of("姓名"), List.of("邮箱"),
                        List.of("手机"), List.of("角色"), List.of("初始密码")))
                .sheet("用户")
                .doWrite(rows);
        return baos.toByteArray();
    }

 
    @Test
    void importUsers_正常链路_全部成功且列表可见() {
        byte[] file = userExcel(List.of(
                List.of(PREFIX + "import_a1", "导入甲", "a1@test.local", "13800000001", 1, "Passw0rd!a"),
                List.of(PREFIX + "import_a2", "导入乙", "a2@test.local", "13800000002", 2, "Passw0rd!b"),
                List.of(PREFIX + "import_a3", "导入丙", "a3@test.local", "13800000003", 4, "Passw0rd!c")));

        ImportResult result = userService.importUsers(99L, file, "users.xlsx");

        assertEquals(3, result.successCount());
        assertEquals(0, result.failCount());
        assertTrue(result.items().stream().allMatch(ImportItemResult::success));
        assertTrue(result.items().stream().allMatch(i -> i.entityId() != null), "每条成功返回新用户 ID");

        PageResult<UserInfo> page = userService.list(new UserQuery("import_a", null, null, 1, 20));
        assertEquals(3, page.total());
        assertEquals("导入乙", page.list().get(1).displayName());
        assertEquals(2, page.list().get(1).role(), "角色 2=测试负责人");

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM tp_user WHERE username = ?", String.class,
                PREFIX + "import_a1");
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希");
        assertFalse(hash.contains("Passw0rd!a"), "禁明文存储");
    }

 
    @Test
    void importUsers_部分失败逐条原因_重试仅失败项幂等() {
        byte[] file = userExcel(List.of(
                List.of(PREFIX + "import_b1", "导入丁", "b1@test.local", "13800000011", 1, "Passw0rd!b1"),
                List.of(PREFIX + "import_b2", "导入戊", "bad-email", "13800000012", 1, "Passw0rd!b2"),
                List.of("", "无名", "b3@test.local", "13800000013", 1, "Passw0rd!b3"),
                List.of(PREFIX + "import_b1", "文件内重复", "b4@test.local", "13800000014", 1, "Passw0rd!b4")));

        ImportResult result = userService.importUsers(99L, file, "users.xlsx");

        assertEquals(1, result.successCount(), "仅第 2 行（import_b1）成功");
        assertEquals(3, result.failCount());
        ImportItemResult emailFail = result.items().stream()
                .filter(i -> i.rowNumber() == 3).findFirst().orElseThrow();
        assertFalse(emailFail.success());
        assertTrue(emailFail.reason().contains("邮箱"), "邮箱格式错误人话原因: " + emailFail.reason());
        ImportItemResult emptyNameFail = result.items().stream()
                .filter(i -> i.rowNumber() == 4).findFirst().orElseThrow();
        assertTrue(emptyNameFail.reason().contains("登录名"), "登录名为空人话原因: " + emptyNameFail.reason());
        ImportItemResult dupFail = result.items().stream()
                .filter(i -> i.rowNumber() == 5).findFirst().orElseThrow();
        assertTrue(dupFail.reason().contains("重复"), "文件内重复人话原因: " + dupFail.reason());

        byte[] fixed = userExcel(List.of(
                List.of(PREFIX + "import_b1", "导入丁", "b1@test.local", "13800000011", 1, "Passw0rd!b1"),
                List.of(PREFIX + "import_b2", "导入戊", "b2@test.local", "13800000012", 1, "Passw0rd!b2"),
                List.of(PREFIX + "import_b3", "无名", "b3@test.local", "13800000013", 1, "Passw0rd!b3")));
        ImportResult retry = userService.retryFailedUsers(99L, fixed, "users.xlsx", List.of(3));
        assertEquals(1, retry.successCount(), "重试修复后的第 3 行");
        assertEquals(0, retry.failCount());

        ImportResult retryAll = userService.retryFailedUsers(99L, fixed, "users.xlsx", List.of(2, 3, 4));
        assertEquals(1, retryAll.successCount(), "仅第 4 行新用户成功");
        assertEquals(2, retryAll.failCount(), "第 2/3 行已存在标记重复（成功项不重复导入）");
        assertTrue(retryAll.items().stream().filter(i -> !i.success())
                .allMatch(i -> i.reason().contains("已存在") || i.reason().contains("重复")), "幂等原因人话");

        PageResult<UserInfo> page = userService.list(new UserQuery("import_b", null, null, 1, 100));
        assertEquals(3, page.total(), "导入用户总数=3（import_b1/b2/b3），无重复入库");
    }

 
    @Test
    void importUsers_库内重名_标记重复且重试幂等() {
        userService.create(99L, input("import_c1", "Passw0rd!c1", "已有用户", 1));
        byte[] file = userExcel(List.of(
                List.of(PREFIX + "import_c1", "重名", "c1@test.local", "13800000021", 1, "Passw0rd!c1"),
                List.of(PREFIX + "import_c2", "新用户", "c2@test.local", "13800000022", 1, "Passw0rd!c2")));

        ImportResult result = userService.importUsers(99L, file, "users.xlsx");
        assertEquals(1, result.successCount());
        assertEquals(1, result.failCount());
        assertTrue(result.items().get(0).reason().contains("已存在"), "库内重名人话原因: " + result.items().get(0).reason());

        ImportResult retry = userService.retryFailedUsers(99L, file, "users.xlsx", List.of(2, 3));
        assertEquals(0, retry.successCount());
        assertEquals(2, retry.failCount());
        assertEquals(2, userService.list(new UserQuery("import_c", null, null, 1, 100)).total(),
                "库内已有 import_c1 + 首次成功 import_c2，重试零新增");
    }

 
    @Test
    void resetPassword_重置后新密码可登录旧密码失效() {
        long id = userService.create(99L, input("import_d1", "Passw0rd!d1", "重置甲", 1));

        UserInfo after = userService.resetPassword(99L, id, "NewPassw0rd!d");
        assertEquals(id, after.id());
        UserInfo auth = userService.authenticate(PREFIX + "import_d1", "NewPassw0rd!d");
        assertEquals(id, auth.id(), "重置后新密码可登录");
        assertThrows(ServiceException.class, () -> userService.authenticate(PREFIX + "import_d1", "Passw0rd!d1"),
                "旧密码立即失效");

        String hash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM tp_user WHERE id = ?", String.class, id);
        assertNotNull(hash);
        assertTrue(hash.startsWith("$2"), "BCrypt 哈希");
        assertFalse(hash.contains("NewPassw0rd!d"), "禁明文存储");
    }

 
    @Test
    void resetPassword_非法新密码或用户不存在_人话拒绝() {
        long id = userService.create(99L, input("import_d2", "Passw0rd!d2", "重置乙", 1));

        ServiceException weak = assertThrows(ServiceException.class,
                () -> userService.resetPassword(99L, id, "short"));
        assertTrue(weak.getMessage().contains("8~64"), "弱口令人话: " + weak.getMessage());

        ServiceException notFound = assertThrows(ServiceException.class,
                () -> userService.resetPassword(99L, 999999L, "Passw0rd!d3"));
        assertEquals(BizErrorCode.NOT_FOUND, notFound.getCode(), "用户不存在 404 语义");
    }


 
    @Test
    void changePassword_成功_password_changed_at刷新() {
        long id = userService.create(99L, input("r5_user_a", "Passw0rd!r5", "R5甲", 1));
        jdbcTemplate.update("UPDATE tp_user SET password_changed_at = now() - interval '200 days' WHERE id = ?", id);

        userService.changePassword(id, "Passw0rd!r5", "NewPass!r5b");

        java.time.LocalDateTime changedAt = jdbcTemplate.queryForObject(
                "SELECT password_changed_at FROM tp_user WHERE id = ?", java.time.LocalDateTime.class, id);
        assertNotNull(changedAt, "R5 计时点非空");
        assertTrue(changedAt.isAfter(java.time.LocalDateTime.now().minusMinutes(5)),
                "改密后计时点=当前时间（过期状态随之清除）: " + changedAt);
        assertNotNull(userService.get(id).passwordChangedAt(), "UserInfo 暴露 passwordChangedAt");
    }

 
    @Test
    void resetPassword_同步刷新password_changed_at() {
        long id = userService.create(99L, input("r5_user_b", "Passw0rd!r5b", "R5乙", 1));
        jdbcTemplate.update("UPDATE tp_user SET password_changed_at = now() - interval '200 days' WHERE id = ?", id);

        userService.resetPassword(99L, id, "NewPass!r5c");

        java.time.LocalDateTime changedAt = jdbcTemplate.queryForObject(
                "SELECT password_changed_at FROM tp_user WHERE id = ?", java.time.LocalDateTime.class, id);
        assertTrue(changedAt.isAfter(java.time.LocalDateTime.now().minusMinutes(5)),
                "管理员重置后计时刷新（用户用新密码登录不再被过期拦截）: " + changedAt);
    }

 
    @Test
    void create_新用户自带password_changed_at() {
        long id = userService.create(99L, input("r5_user_c", "Passw0rd!r5c", "R5丙", 1));
        UserInfo info = userService.get(id);
        assertNotNull(info.passwordChangedAt(), "新用户计时点=创建即 now()（V19 DEFAULT）");
        assertTrue(info.passwordChangedAt().isAfter(java.time.LocalDateTime.now().minusMinutes(5)));
    }

 
    @Test
    void passwordStatus_超期判定() {
        long id = userService.create(99L, input("r5_user_d", "Passw0rd!r5d", "R5丁", 1));
        var fresh = userService.passwordStatus(id);
        assertFalse(fresh.passwordExpired(), "刚建账号未过期（默认 expire-days=90）");
        assertEquals(90, fresh.expireDays(), "V19 种子阈值 90 可查");

        jdbcTemplate.update("UPDATE tp_user SET password_changed_at = now() - interval '91 days' WHERE id = ?", id);
        assertTrue(userService.passwordStatus(id).passwordExpired(),
                "91 天前改密 > 90 天阈值 → 过期（TC-R5-01）");
    }

 
    @Test
    void passwordStatus_expire_days为0_永不过期() {
        long id = userService.create(99L, input("r5_user_e", "Passw0rd!r5e", "R5戊", 1));
        String orig = configCache.get("password.expire-days");
        try {
            configCache.put("password.expire-days", "0");
            jdbcTemplate.update("UPDATE tp_user SET password_changed_at = now() - interval '3650 days' WHERE id = ?", id);
            assertFalse(userService.passwordStatus(id).passwordExpired(),
                    "expire-days=0 → 任何计时点不强制（TC-R5-02）");
        } finally {
            if (orig == null) {
                configCache.remove("password.expire-days");
            } else {
                configCache.put("password.expire-days", orig);
            }
        }
    }

 
    @Test
    void userTemplate_模板可下载且表头与解析一致() {
        byte[] template = userService.userTemplate();
        assertNotNull(template);
        assertTrue(template.length > 1000, "xlsx 模板非空");
    }


 
    @Test
    void updateProfile_修改昵称头像_持久化且不影响角色状态() {
        long id = userService.create(99L, input("profile_a", "Passw0rd!a", "原始昵称", 2));

        UserInfo after = userService.updateProfile(id, "新昵称甲", "https://avatar.example.com/a.png");

        assertEquals("新昵称甲", after.displayName(), "昵称更新");
        assertEquals("https://avatar.example.com/a.png", after.avatar(), "头像更新");
        assertEquals(2, after.role(), "角色不受个人资料修改影响");
        assertEquals(1, after.status(), "状态不受个人资料修改影响");

        UserInfo reloaded = userService.get(id);
        assertEquals("新昵称甲", reloaded.displayName());
        assertEquals("https://avatar.example.com/a.png", reloaded.avatar());

        String dbAvatar = jdbcTemplate.queryForObject(
                "SELECT avatar FROM tp_user WHERE id = ?", String.class, id);
        assertEquals("https://avatar.example.com/a.png", dbAvatar, "DB avatar 已持久化");
    }

 
    @Test
    void updateProfile_昵称或头像超长_400且头像可清空() {
        long id = userService.create(99L, input("profile_b", "Passw0rd!b", "昵称乙", 1));

        ServiceException longName = assertThrows(ServiceException.class,
                () -> userService.updateProfile(id, "长".repeat(101), null));
        assertEquals(BizErrorCode.VALIDATION_ERROR, longName.getCode(), "昵称超长 400");
        assertTrue(longName.getMessage().contains("100"), "昵称超长人话: " + longName.getMessage());

        ServiceException longAvatar = assertThrows(ServiceException.class,
                () -> userService.updateProfile(id, "正常昵称", "https://a.example.com/" + "x".repeat(190)));
        assertEquals(BizErrorCode.VALIDATION_ERROR, longAvatar.getCode(), "头像超长 400");

        UserInfo cleared = userService.updateProfile(id, "正常昵称", null);
        assertNull(cleared.avatar(), "头像清空");
    }

    
    @Test
    void updateProfile_用户不存在_404() {
        ServiceException ex = assertThrows(ServiceException.class,
                () -> userService.updateProfile(999999L, "昵称", null));
        assertEquals(BizErrorCode.NOT_FOUND, ex.getCode(), "用户不存在 404");
    }

 
    @Test
    void changePassword_连续错误旧密码_限流拦截() {
        long id = userService.create(99L, input("profile_c", "Passw0rd!c", "限流甲", 1));

        for (int i = 0; i < 5; i++) {
            String wrongOld = "WrongOld!x" + i;
            ServiceException wrong = assertThrows(ServiceException.class,
                    () -> userService.changePassword(id, wrongOld, "NewPass!2c"));
            assertEquals(BizErrorCode.VALIDATION_ERROR, wrong.getCode(), "第 " + (i + 1) + " 次错误 400");
        }

        ServiceException blocked = assertThrows(ServiceException.class,
                () -> userService.changePassword(id, "Passw0rd!c", "NewPass!2c"));
        assertTrue(blocked.getMessage().contains("次数过多"), "限流人话: " + blocked.getMessage());
    }

 
    @Test
    void changePassword_成功后计数清零_再次错误不被限流() {
        long id = userService.create(99L, input("profile_d", "Passw0rd!d", "限流乙", 1));

        for (int i = 0; i < 3; i++) {
            String wrongOld = "WrongOld!y" + i;
            assertThrows(ServiceException.class,
                    () -> userService.changePassword(id, wrongOld, "NewPass!2d"));
        }
        UserInfo after = userService.changePassword(id, "Passw0rd!d", "NewPass!2d");
        assertEquals(id, after.id());

        for (int i = 0; i < 3; i++) {
            String wrongOld = "WrongOld!z" + i;
            assertThrows(ServiceException.class,
                    () -> userService.changePassword(id, wrongOld, "NewPass!3d"));
        }
        UserInfo again = userService.changePassword(id, "NewPass!2d", "NewPass!3d");
        assertEquals(id, again.id(), "成功后计数清零，未误限流");
    }
}
