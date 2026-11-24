package com.sixbean.testpike.core.rbac.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.rbac.LegacyPermMapping;
import com.sixbean.testpike.common.rbac.PermissionCatalog;
import com.sixbean.testpike.common.rbac.PermissionResolver;
import com.sixbean.testpike.common.rbac.RoleTemplate;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.rbac.api.dto.PermGroupInfo;
import com.sixbean.testpike.core.rbac.api.dto.PermInfo;
import com.sixbean.testpike.core.rbac.api.dto.PermItemInfo;
import com.sixbean.testpike.core.rbac.api.dto.RoleInfo;
import com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideInput;
import com.sixbean.testpike.core.rbac.api.dto.RolePermOverrideView;
import com.sixbean.testpike.core.rbac.api.dto.RolePermissionsInput;
import com.sixbean.testpike.core.rbac.api.dto.RoleSaveInput;
import com.sixbean.testpike.core.rbac.domain.component.PermCatalog;
import com.sixbean.testpike.core.rbac.domain.service.RoleService;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;
import com.sixbean.testpike.core.rbac.domain.component.RoleRefusals;

/**
 * <ul>
 * </ul>
 *
 */
@Service
public class RoleServiceImpl implements RoleService {

    
    public static final String ADMIN_ROLE_CODE = "admin";

 
    private static final String[] LEGACY_ROLE_CODES = {null, "tester", "test_lead", "developer", "admin"};

    
    private static final String[] LEGACY_CODES_BY_ROLE = {null, "tester", "test_lead", "developer", "admin"};

    private final JdbcTemplate jdbcTemplate;

    public RoleServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }


    @Override
    public List<RoleInfo> roles() {
        return jdbcTemplate.query(
                "SELECT * FROM tp_role ORDER BY id", this::mapRow);
    }

    @Override
    public RoleInfo get(long roleId) {
        List<RoleInfo> rows = jdbcTemplate.query(
                "SELECT * FROM tp_role WHERE id = ?", this::mapRow, roleId);
        if (rows.isEmpty()) {
            throw new ServiceException(RoleRefusals.roleNotFound(roleId), BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    @Audit(objectType = "role", action = "create",
            objectId = "#result ?: #input.code()", after = "#result")
    public long create(RoleSaveInput input) {
        validateSaveInput(input);
        try {
            return Objects.requireNonNull(jdbcTemplate.queryForObject("""
                    INSERT INTO tp_role (name, code, description, data_scope, status, builtin)
                    VALUES (?, ?, ?, ?, ?, 0) RETURNING id
                    """, Long.class, trimToNull(input.name()), trimToNull(input.code()),
                    trimToNull(input.description()), input.dataScope(), input.status()));
        } catch (DuplicateKeyException e) {
            throw new ServiceException(RoleRefusals.codeExists(trimToNull(input.code())),
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    @Audit(objectType = "role", action = "update", objectId = "#roleId", after = "#result")
    public RoleInfo update(long roleId, RoleSaveInput input) {
        RoleInfo current = get(roleId);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        validateSaveInput(input);
        String code = trimToNull(input.code());
        if (current.builtin() == 1 && code != null && !code.equals(current.code())) {
            throw new ServiceException(RoleRefusals.builtinCodeLocked(current.code()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        String name = trimToNull(input.name());
        jdbcTemplate.update("""
                UPDATE tp_role SET name = ?, description = ?, data_scope = ?, status = ?,
                updated_at = now() WHERE id = ?
                """, name == null ? current.name() : name, trimToNull(input.description()),
                input.dataScope(), input.status(), roleId);
        return get(roleId);
    }

    @Override
    @Audit(objectType = "role", action = "delete", objectId = "#roleId", after = "")
    public void delete(long roleId) {
        RoleInfo current = get(roleId);
        if (current.builtin() == 1) {
            throw new ServiceException(RoleRefusals.builtinDeleteBlocked(current.name()),
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        Long refCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_user_role WHERE role_id = ?", Long.class, roleId);
        if (refCount != null && refCount > 0) {
            throw new ServiceException("角色「%s」已分配给 %s 个用户，请先解除分配",
                    BizErrorCode.RESOURCE_CONFLICT, current.name(), refCount);
        }
        jdbcTemplate.update("DELETE FROM tp_role_func WHERE role_id = ?", roleId);
        jdbcTemplate.update("DELETE FROM tp_role_permission WHERE role_id = ?", roleId);
        jdbcTemplate.update("DELETE FROM tp_role WHERE id = ?", roleId);
    }


    @Override
    public List<String> funcsOfRole(long roleId) {
        get(roleId);
        return jdbcTemplate.queryForList(
                "SELECT perm_code FROM tp_role_func WHERE role_id = ? ORDER BY perm_code",
                String.class, roleId);
    }

    @Override
    @Audit(objectType = "role", action = "update_funcs", objectId = "#roleId", after = "")
    public void updateFuncs(long roleId, List<String> permCodes) {
        get(roleId);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(Map.of("permCodes",
                jdbcTemplate.queryForList(
                        "SELECT perm_code FROM tp_role_func WHERE role_id = ? ORDER BY perm_code",
                        String.class, roleId))));
        List<String> codes = permCodes == null ? List.of() : permCodes;
        for (String code : codes) {
            if (!PermCatalog.exists(code)) {
                throw new ServiceException(RoleRefusals.permCodeUnknown(code),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        Set<String> unique = new HashSet<>(codes);
        jdbcTemplate.update("DELETE FROM tp_role_func WHERE role_id = ?", roleId);
        if (!unique.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO tp_role_func (role_id, perm_code) VALUES (?, ?)",
                    unique.stream().map(c -> new Object[]{roleId, c}).toList());
        }
    }


    @Override
    public List<RoleInfo> rolesOfUser(long userId) {
        List<RoleInfo> rows = jdbcTemplate.query("""
                SELECT r.* FROM tp_user_role ur
                JOIN tp_role r ON r.id = ur.role_id
                WHERE ur.user_id = ? ORDER BY ur.role_id
                """, this::mapRow, userId);
        if (!rows.isEmpty()) {
            return rows;
        }
        Integer legacyRole = jdbcTemplate.queryForObject(
                "SELECT role FROM tp_user WHERE id = ?", Integer.class, userId);
        if (legacyRole != null && legacyRole >= 1 && legacyRole <= 4) {
            List<RoleInfo> legacy = jdbcTemplate.query(
                    "SELECT * FROM tp_role WHERE code = ?", this::mapRow, LEGACY_ROLE_CODES[legacyRole]);
            if (!legacy.isEmpty()) {
                return legacy;
            }
        }
        return List.of();
    }

    @Override
    @Audit(objectType = "user", action = "assign_roles", objectId = "#userId", after = "")
    @Transactional
    public void assignUserRoles(long userId, List<Long> roleIds) {
        List<Long> ids = roleIds == null ? List.of()
                : roleIds.stream().filter(Objects::nonNull).distinct().toList();
        for (Long roleId : ids) {
            get(roleId);
        }
        jdbcTemplate.update("DELETE FROM tp_user_role WHERE user_id = ?", userId);
        if (!ids.isEmpty()) {
            jdbcTemplate.batchUpdate(
                    "INSERT INTO tp_user_role (user_id, role_id) VALUES (?, ?)",
                    ids.stream().map(id -> new Object[]{userId, id}).toList());
        }
        int legacyRole = 0;
        if (!ids.isEmpty()) {
            String code = jdbcTemplate.queryForObject(
                    "SELECT code FROM tp_role WHERE id = ?", String.class, ids.get(0));
            legacyRole = legacyRoleNumber(code);
        }
        jdbcTemplate.update("UPDATE tp_user SET role = ?, updated_at = now() WHERE id = ?",
                legacyRole, userId);
    }


    @Override
    public Set<String> permsOfUser(long userId) {
        List<String> codes = rolesOfUser(userId).stream().map(RoleInfo::code).toList();
        if (codes.isEmpty()) {
            return Set.of();
        }
        Set<String> legacyFuncs = legacyFuncKeys(codes);
        Set<String> baseline = new HashSet<>(legacyFuncs);
        baseline.addAll(LegacyPermMapping.project(legacyFuncs));
        if (codes.contains(ADMIN_ROLE_CODE)) {
            baseline.addAll(PermissionCatalog.allKeys());
            baseline.addAll(PermCatalog.allCodes());
        } else {
            for (String code : codes) {
                RoleTemplate template = RoleTemplate.fromCode(code);
                if (template != null) {
                    baseline.addAll(template.baselineKeys());
                }
            }
        }
        return PermissionResolver.resolve(baseline, overrideKeys(userId, false), overrideKeys(userId, true));
    }

    
    private Set<String> overrideKeys(long userId, boolean deny) {
        return new HashSet<>(jdbcTemplate.queryForList("""
                SELECT rp.perm_key FROM tp_role_permission rp
                JOIN tp_user_role ur ON ur.role_id = rp.role_id
                WHERE ur.user_id = ? AND rp.effect = ?
                """, String.class, userId, deny ? 2 : 1));
    }

 
    private Set<String> legacyFuncKeys(List<String> codes) {
        Set<String> legacy = new HashSet<>();
        for (String code : codes) {
            legacy.addAll(jdbcTemplate.queryForList("""
                    SELECT rf.perm_code FROM tp_role_func rf
                    JOIN tp_role r ON r.id = rf.role_id AND r.code = ?
                    """, String.class, code));
        }
        return legacy;
    }

    @Override
    public int dataScopeOfUser(long userId) {
        List<RoleInfo> roles = rolesOfUser(userId);
        if (roles.isEmpty()) {
            return 2;
        }
        return roles.stream().mapToInt(RoleInfo::dataScope).min().orElse(2);
    }

    @Override
    public List<PermInfo> permCatalog() {
        return PermCatalog.catalog();
    }


    @Override
    public List<PermGroupInfo> permCatalogGrouped() {
        return PermissionCatalog.groupedByDomain().entrySet().stream()
                .map(e -> new PermGroupInfo(e.getKey().token(), e.getKey().label(),
                        e.getValue().stream()
                                .map(p -> new PermItemInfo(p.key(), p.label(),
                                        p.family().name(), p.functionId()))
                                .toList()))
                .toList();
    }

    @Override
    public List<RolePermOverrideView> permissionsOfRole(long roleId) {
        get(roleId);
        return listOverrides(roleId);
    }

    @Override
    @Audit(objectType = "role", action = "update_permissions", objectId = "#roleId", after = "#result")
    @Transactional
    public List<RolePermOverrideView> savePermissions(long roleId, RolePermissionsInput input) {
        get(roleId);
        List<RolePermOverrideInput> items = input.items() == null ? List.of() : input.items();
        if (items.isEmpty() && !input.confirm()) {
            throw new ServiceException(RoleRefusals.emptyOverridesNeedConfirm(roleId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<String> allow = new HashSet<>();
        Set<String> deny = new HashSet<>();
        for (RolePermOverrideInput item : items) {
            if (item.effect() != 1 && item.effect() != 2) {
                throw new ServiceException(RoleRefusals.effectOutOfRange(item.effect(), item.permKey()),
                        BizErrorCode.VALIDATION_ERROR);
            }
            Set<String> target = item.effect() == 1 ? allow : deny;
            if (!target.add(item.permKey())) {
                continue;
            }
            Set<String> opposite = item.effect() == 1 ? deny : allow;
            if (opposite.contains(item.permKey())) {
                throw new ServiceException(RoleRefusals.allowDenyConflict(item.permKey()),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        PermissionResolver.resolve(Set.<String>of(), allow, deny);
        AuditLogContext.setBefore(Map.of("overrides", listOverrides(roleId)));
        jdbcTemplate.update("DELETE FROM tp_role_permission WHERE role_id = ?", roleId);
        Long operatorId = OperatorContext.currentOperatorId();
        List<Object[]> rows = new ArrayList<>();
        allow.forEach(k -> rows.add(new Object[]{roleId, k, 1, operatorId}));
        deny.forEach(k -> rows.add(new Object[]{roleId, k, 2, operatorId}));
        if (!rows.isEmpty()) {
            jdbcTemplate.batchUpdate("""
                    INSERT INTO tp_role_permission (role_id, perm_key, effect, created_by)
                    VALUES (?, ?, ?, ?)
                    """, rows);
        }
        return listOverrides(roleId);
    }

    
    private List<RolePermOverrideView> listOverrides(long roleId) {
        return jdbcTemplate.query("""
                SELECT perm_key, effect FROM tp_role_permission
                WHERE role_id = ? ORDER BY perm_key, effect
                """, (rs, n) -> new RolePermOverrideView(rs.getString("perm_key"), rs.getInt("effect")),
                roleId);
    }


    private RoleInfo mapRow(ResultSet rs, int rowNum) throws SQLException {
        return new RoleInfo(
                rs.getLong("id"),
                rs.getString("name"),
                rs.getString("code"),
                rs.getString("description"),
                rs.getInt("data_scope"),
                rs.getInt("status"),
                rs.getInt("builtin"),
                rs.getObject("created_at", LocalDateTime.class));
    }

    private static void validateSaveInput(RoleSaveInput input) {
        if (trimToNull(input.name()) == null) {
            throw new ServiceException(RoleRefusals.roleNameMissing(input.name()), BizErrorCode.VALIDATION_ERROR);
        }
        if (trimToNull(input.code()) == null || trimToNull(input.code()).length() > 50) {
            throw new ServiceException(RoleRefusals.roleCodeInvalid(input.code()), BizErrorCode.VALIDATION_ERROR);
        }
        if (input.dataScope() < 1 || input.dataScope() > 3) {
            throw new ServiceException(RoleRefusals.dataScopeInvalid(input.dataScope()),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (input.status() != 0 && input.status() != 1) {
            throw new ServiceException(RoleRefusals.statusInvalid(input.status()), BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private static int legacyRoleNumber(String code) {
        for (int i = 1; i < LEGACY_CODES_BY_ROLE.length; i++) {
            if (LEGACY_CODES_BY_ROLE[i].equals(code)) {
                return i;
            }
        }
        return 0;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
