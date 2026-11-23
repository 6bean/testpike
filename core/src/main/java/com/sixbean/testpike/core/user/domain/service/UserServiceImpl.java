package com.sixbean.testpike.core.user.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.component.ExcelTemplateBuilder;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.imports.api.dto.ImportItemResult;
import com.sixbean.testpike.core.imports.api.dto.ImportResult;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import com.sixbean.testpike.core.user.api.dto.UserCreateInput;
import com.sixbean.testpike.core.user.api.dto.UserImportRow;
import com.sixbean.testpike.core.user.api.dto.UserInfo;
import com.sixbean.testpike.core.user.api.dto.UserQuery;
import com.sixbean.testpike.core.user.api.dto.UserUpdateInput;
import com.sixbean.testpike.core.user.domain.component.PasswordAttemptGuard;
import com.sixbean.testpike.core.user.domain.component.UserExcelParser;
import com.sixbean.testpike.core.user.domain.component.UserValidationRules;


import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;


@Service
public class UserServiceImpl implements UserService {

    private static final Logger log = LoggerFactory.getLogger(UserServiceImpl.class);

 
    private static final String LOGIN_FAILED_MSG = "用户名或密码错误";
 
    private static final String[] LEGACY_ROLE_CODES = {"tester", "test_lead", "developer", "admin"};

    /** Server-side sort whitelist (user list; frontend column props map onto
     * these keys via SORT_KEY_MAP). Values are physical columns — never user
     * input. lockStatus has no physical column (derived from locked_until), so
     * it stays out of the whitelist and the page never emits a sort for it. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "username", "username",
            "display_name", "display_name",
            "email", "email",
            "role", "role",
            "status", "status",
            "created_at", "created_at");

    private final JdbcTemplate jdbcTemplate;
    private final BCryptPasswordEncoder passwordEncoder;
    private final RoleService roleService;
    private final UserExcelParser userExcelParser;
    private final PasswordAttemptGuard passwordAttemptGuard;
    private final ConfigService configService;
    private final LoginLockService loginLockService;
    private final com.sixbean.testpike.core.user.domain.component.PasswordExpiryPolicy passwordExpiryPolicy;

    public UserServiceImpl(JdbcTemplate jdbcTemplate, BCryptPasswordEncoder passwordEncoder,
                           RoleService roleService, UserExcelParser userExcelParser,
                           PasswordAttemptGuard passwordAttemptGuard,
                           ConfigService configService, LoginLockService loginLockService,
                           com.sixbean.testpike.core.user.domain.component.PasswordExpiryPolicy passwordExpiryPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.roleService = roleService;
        this.userExcelParser = userExcelParser;
        this.passwordAttemptGuard = passwordAttemptGuard;
        this.configService = configService;
        this.loginLockService = loginLockService;
        this.passwordExpiryPolicy = passwordExpiryPolicy;
    }

    
    public List<com.sixbean.testpike.core.rbac.api.dto.RoleInfo> roles() {
        return roleService.roles();
    }

    
    @Audit(objectType = "user", action = "create", objectId = "#result ?: #input.username()", after = "#result")
    @Transactional
    public long create(Long createdBy, UserCreateInput input) {
        String username = trimToNull(input.username());
        String password = input.password();
        String displayName = trimToNull(input.displayName());
        if (username == null || username.length() > 50) {
            throw new ServiceException("登录名不能为空且不超过 50 字符", BizErrorCode.VALIDATION_ERROR);
        }
        validatePassword(password, "初始密码");
        validateRole(input.role());
        String display = displayName == null ? username : displayName;
        long newUserId;
        try {
            newUserId = Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    INSERT INTO tp_user (username, password_hash, display_name, email, phone, role, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?) RETURNING id
                    """, Long.class, username, passwordEncoder.encode(password), display,
                    trimToNull(input.email()), trimToNull(input.phone()), input.role(), createdBy));
        } catch (DuplicateKeyException e) {
            throw new ServiceException("登录名 %s 已存在，请更换", BizErrorCode.RESOURCE_CONFLICT, username);
        }
        syncPrimaryRole(newUserId, input.role());
        return newUserId;
    }

 
    @Audit(objectType = "user", action = "update", objectId = "#userId", after = "#result")
    public UserInfo update(Long operatorId, long userId, UserUpdateInput input) {
        get(userId);
        validateRole(input.role());
        if (input.status() != 0 && input.status() != 1) {
            throw new ServiceException("状态仅支持 1=启用 / 0=停用", BizErrorCode.VALIDATION_ERROR);
        }
        String displayName = trimToNull(input.displayName());
        jdbcTemplate.update("""
                UPDATE tp_user SET display_name = ?, email = ?, phone = ?, role = ?, status = ?,
                updated_at = now() WHERE id = ?
                """, displayName == null ? get(userId).displayName() : displayName,
                trimToNull(input.email()), trimToNull(input.phone()), input.role(), input.status(), userId);
        syncPrimaryRole(userId, input.role());
        return get(userId);
    }

 
    @Audit(objectType = "user", action = "change_password", objectId = "#userId", after = "#result")
    public UserInfo changePassword(long userId, String oldPassword, String newPassword) {
        passwordAttemptGuard.check(userId);
        UserInfo info = get(userId);
        if (oldPassword == null || !passwordEncoder.matches(oldPassword, info.passwordHash())) {
            passwordAttemptGuard.recordFailure(userId);
            throw new ServiceException("原密码不正确", BizErrorCode.VALIDATION_ERROR);
        }
        validatePassword(newPassword, "新密码");
        if (passwordEncoder.matches(newPassword, info.passwordHash())) {
            throw new ServiceException("新密码不能与原密码相同", BizErrorCode.VALIDATION_ERROR);
        }
        jdbcTemplate.update("UPDATE tp_user SET password_hash = ?, password_changed_at = now(), updated_at = now() WHERE id = ?",
                passwordEncoder.encode(newPassword), userId);
        passwordAttemptGuard.clear(userId);
        return get(userId);
    }

    
    @Audit(objectType = "user", action = "update_profile", objectId = "#userId", after = "#result")
    public UserInfo updateProfile(long userId, String displayName, String avatar) {
        UserInfo info = get(userId);
        String newDisplayName = trimToNull(displayName);
        if (newDisplayName != null && newDisplayName.length() > 100) {
            throw new ServiceException("昵称不能超过 100 字符", BizErrorCode.VALIDATION_ERROR);
        }
        String newAvatar = trimToNull(avatar);
        if (newAvatar != null && newAvatar.length() > 200) {
            throw new ServiceException("头像地址不能超过 200 字符", BizErrorCode.VALIDATION_ERROR);
        }
        jdbcTemplate.update("""
                UPDATE tp_user SET display_name = ?, avatar = ?, updated_at = now() WHERE id = ?
                """, newDisplayName == null ? info.displayName() : newDisplayName, newAvatar, userId);
        return get(userId);
    }

    
    @Audit(objectType = "user", action = "delete", objectId = "#userId", after = "#result")
    public UserInfo delete(Long operatorId, long userId) {
        get(userId);
        if (operatorId != null && operatorId == userId) {
            throw new ServiceException("不能删除当前登录账号，请先移交管理员职责", BizErrorCode.FORBIDDEN);
        }
        jdbcTemplate.update("UPDATE tp_user SET deleted = 1, updated_at = now() WHERE id = ?", userId);
        return get(userId);
    }

    
    @Override
    @Audit(objectType = "user_import", action = "import", objectId = "'批量导入用户'", after = "#result")
    public ImportResult importUsers(Long operatorId, byte[] file, String fileName) {
        List<UserImportRow> rows = userExcelParser.parse(file, fileName);
        Set<String> existingUsernames = loadUsernames();
        List<ImportItemResult> items = new ArrayList<>();
        int success = 0;
        int fail = 0;
        for (UserImportRow row : rows) {
            if (row.failReason() != null) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, row.failReason(), null));
                continue;
            }
            if (existingUsernames.contains(row.username())) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false,
                        "登录名「" + row.username() + "」已存在（重复），未导入（成功项不重复导入）", null));
                continue;
            }
            try {
                long id = create(operatorId, new UserCreateInput(row.username(), row.password(),
                        row.displayName(), row.email(), row.phone(), row.role() == null ? 1 : row.role()));
                success++;
                existingUsernames.add(row.username());
                items.add(new ImportItemResult(row.rowNumber(), true, null, id));
            } catch (ServiceException e) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, e.getMessage(), null));
            }
        }
        return new ImportResult(success, fail, items);
    }

    
    @Override
    @Audit(objectType = "user_import", action = "import_retry", objectId = "'批量导入用户'", after = "#result")
    public ImportResult retryFailedUsers(Long operatorId, byte[] file, String fileName,
                                         List<Integer> retryRows) {
        if (retryRows == null || retryRows.isEmpty()) {
            throw new ServiceException("请选择需要重试的失败项（行号不能为空）", BizErrorCode.VALIDATION_ERROR);
        }
        List<UserImportRow> rows = userExcelParser.parse(file, fileName);
        Set<Integer> retrySet = new HashSet<>(retryRows);
        Set<String> existingUsernames = loadUsernames();
        List<ImportItemResult> items = new ArrayList<>();
        int success = 0;
        int fail = 0;
        for (UserImportRow row : rows) {
            if (!retrySet.contains(row.rowNumber())) {
                continue;
            }
            if (row.failReason() != null) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, row.failReason(), null));
                continue;
            }
            if (existingUsernames.contains(row.username())) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false,
                        "登录名「" + row.username() + "」已存在（重复），未导入（成功项不重复导入）", null));
                continue;
            }
            try {
                long id = create(operatorId, new UserCreateInput(row.username(), row.password(),
                        row.displayName(), row.email(), row.phone(), row.role() == null ? 1 : row.role()));
                success++;
                existingUsernames.add(row.username());
                items.add(new ImportItemResult(row.rowNumber(), true, null, id));
            } catch (ServiceException e) {
                fail++;
                items.add(new ImportItemResult(row.rowNumber(), false, e.getMessage(), null));
            }
        }
        return new ImportResult(success, fail, items);
    }

    
    @Override
    public byte[] userTemplate() {
        return ExcelTemplateBuilder.build(UserExcelParser.HEADERS, "用户导入模板");
    }

    
    @Override
    @Audit(objectType = "user", action = "reset_password", objectId = "#userId", after = "#result")
    public UserInfo resetPassword(Long operatorId, long userId, String newPassword) {
        get(userId);
        UserValidationRules.validatePassword(newPassword, "新密码");
        jdbcTemplate.update("UPDATE tp_user SET password_hash = ?, password_changed_at = now(), updated_at = now() WHERE id = ?",
                passwordEncoder.encode(newPassword), userId);
        return get(userId);
    }

    
    private Set<String> loadUsernames() {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT username FROM tp_user WHERE deleted = 0", String.class));
    }

    
    public UserInfo get(long userId) {
        List<UserInfo> rows = jdbcTemplate.query(
                "SELECT * FROM tp_user WHERE id = ?", this::mapRow, userId);
        if (rows.isEmpty()) {
            throw new ServiceException("用户不存在或已被删除", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    
    @Override
    public com.sixbean.testpike.core.user.api.dto.PasswordStatus passwordStatus(long userId) {
        UserInfo info = get(userId);
        return new com.sixbean.testpike.core.user.api.dto.PasswordStatus(
                userId, passwordExpiryPolicy.isExpired(info.passwordChangedAt()),
                passwordExpiryPolicy.expireDays());
    }

    
    public PageResult<UserInfo> list(UserQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = Math.min(Math.max(query.size(), 1), 100);
        int offset = (safePage - 1) * safeSize;

        StringBuilder where = new StringBuilder(" WHERE deleted = 0");
        List<Object> args = new ArrayList<>();
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (username ILIKE ? OR display_name ILIKE ?)");
            String kw = "%" + query.keyword().trim() + "%";
            args.add(kw);
            args.add(kw);
        }
        if (query.role() != null) {
            where.append(" AND role = ?");
            args.add(query.role());
        }
        if (query.status() != null) {
            where.append(" AND status = ?");
            args.add(query.status());
        }

        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user" + where, Long.class, args.toArray());

        // Sort keys must resolve to a physical column through the shared whitelist —
        // user input never reaches the SQL string (SortColumnPolicy.require, same
        // posture as the plan/batch/requirement domains; off-list key = fail-closed 400).
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "用户");
        String order = sortColumn == null
                ? " ORDER BY id"
                : " ORDER BY " + sortColumn
                        + ("asc".equalsIgnoreCase(query.sortDir()) ? " ASC" : " DESC") + ", id ASC";

        List<Object> listArgs = new ArrayList<>(args);
        listArgs.add(safeSize);
        listArgs.add(offset);
        List<UserInfo> list = jdbcTemplate.query(
                "SELECT * FROM tp_user" + where + order + " LIMIT ? OFFSET ?",
                this::mapRow, listArgs.toArray());
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    public UserInfo authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null || password.isBlank()) {
            throw new ServiceException(LOGIN_FAILED_MSG, BizErrorCode.UNAUTHORIZED);
        }
        List<UserInfo> rows = jdbcTemplate.query(
                "SELECT * FROM tp_user WHERE username = ? AND deleted = 0",
                this::mapRow, username.trim());
        if (rows.isEmpty()) {
            throw new ServiceException(LOGIN_FAILED_MSG, BizErrorCode.UNAUTHORIZED);
        }
        UserInfo user = rows.get(0);
        if (user.lockedUntil() != null && user.lockedUntil().isAfter(LocalDateTime.now())) {
            long remainMinutes = Math.max(1, ChronoUnit.MINUTES.between(LocalDateTime.now(), user.lockedUntil()));
            throw new ServiceException("账号已锁定，请 " + remainMinutes + " 分钟后再试或联系管理员",
                    BizErrorCode.LOCKED);
        }
        if (user.lockedUntil() != null) {
            jdbcTemplate.update("UPDATE tp_user SET fail_count = 0, locked_until = NULL, lock_reason = NULL WHERE id = ?",
                    user.id());
            user = new UserInfo(user.id(), user.username(), user.displayName(), user.email(), user.phone(),
                    user.avatar(), user.role(), user.status(), user.deleted(), user.createdBy(),
                    user.createdAt(), user.updatedAt(), 0, null, null, user.passwordHash(),
                    user.passwordChangedAt());
        }
        if (user.status() != 1) {
            throw new ServiceException("该账号已停用，请联系系统管理员", BizErrorCode.FORBIDDEN);
        }
        if (user.passwordHash() == null || !passwordEncoder.matches(password, user.passwordHash())) {
            int maxFail = configInt("testpike.login.max-fail", 5);
            int lockMinutes = configInt("testpike.login.lock-minutes", 15);
            int newCount = user.failCount() + 1;
            if (newCount >= maxFail) {
                loginLockService.applyLock(user.id(), user.username(), newCount, lockMinutes);
            } else {
                jdbcTemplate.update("UPDATE tp_user SET fail_count = ?, updated_at = now() WHERE id = ?",
                        newCount, user.id());
            }
            throw new ServiceException(LOGIN_FAILED_MSG, BizErrorCode.UNAUTHORIZED);
        }
        jdbcTemplate.update("UPDATE tp_user SET fail_count = 0, locked_until = NULL, lock_reason = NULL WHERE id = ?",
                user.id());
        return user;
    }

 
    @Audit(objectType = "user", action = "unlock", objectId = "#userId", after = "#result")
    public UserInfo unlock(Long operatorId, long userId) {
        UserInfo user = get(userId);
        jdbcTemplate.update("""
                UPDATE tp_user SET fail_count = 0, locked_until = NULL, lock_reason = NULL, updated_at = now()
                WHERE id = ?
                """, userId);
        return get(userId);
    }

    
    private int configInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(configService.getValue(key, String.valueOf(defaultValue)));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    
    public boolean existsByUsername(String username) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE username = ? AND deleted = 0", Long.class, username);
        return count != null && count > 0;
    }

    
    public boolean isEmpty() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user WHERE deleted = 0", Long.class);
        return count == null || count == 0;
    }

    
    private void syncPrimaryRole(long userId, int legacyRole) {
        if (legacyRole < 1 || legacyRole > 4) {
            return;
        }
        Long roleId = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_role WHERE code = ?", Long.class, LEGACY_ROLE_CODES[legacyRole - 1]);
        if (roleId != null) {
            roleService.assignUserRoles(userId, List.of(roleId));
        }
    }

    private UserInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        long createdByValue = rs.getLong("created_by");
        boolean createdByNull = rs.wasNull();
        return new UserInfo(
                rs.getLong("id"),
                rs.getString("username"),
                rs.getString("display_name"),
                rs.getString("email"),
                rs.getString("phone"),
                rs.getString("avatar"),
                rs.getInt("role"),
                rs.getInt("status"),
                rs.getInt("deleted"),
                createdByNull ? null : createdByValue,
                rs.getObject("created_at", LocalDateTime.class),
                rs.getObject("updated_at", LocalDateTime.class),
                rs.getInt("fail_count"),
                rs.getObject("locked_until", LocalDateTime.class),
                rs.getString("lock_reason"),
                rs.getString("password_hash"),
                rs.getObject("password_changed_at", LocalDateTime.class));
    }

    private static void validatePassword(String password, String label) {
        UserValidationRules.validatePassword(password, label);
    }

    private static void validateRole(int role) {
        UserValidationRules.validateRole(role);
    }

    private static String trimToNull(String value) {
        return UserValidationRules.trimToNull(value);
    }
}
