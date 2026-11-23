package com.sixbean.testpike.core.token.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.api.dto.AuditLogInput;
import com.sixbean.testpike.core.audit.domain.service.AuditLogService;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreateInput;
import com.sixbean.testpike.core.token.api.dto.ApiTokenCreated;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyResult;
import com.sixbean.testpike.core.token.api.dto.ApiTokenVerifyStatus;
import com.sixbean.testpike.core.token.api.dto.ApiTokenView;
import com.sixbean.testpike.core.token.domain.service.ApiTokenService;
import com.sixbean.testpike.core.token.infrastructure.dataobject.ApiTokenRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Service
public class ApiTokenServiceImpl implements ApiTokenService {

    
    private static final String OBJECT_TYPE = "api_token";
    
    private static final String PLAINTEXT_PREFIX = "tp_pat_";
    
    private static final int RANDOM_BYTES = 32;
    
    private static final String SCOPE_PREFIX = "mcp.";
 
    private static final String SCOPE_CI = "ci";
 
    private static final int SCOPES_MAX = 300;
 
    private static final int NAME_MAX = 100;
 /** GA1 project pin column width (V562; rejected in a human sentence pre-DB). */
    private static final int PROJECT_SCOPE_MAX = 200;

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final NamedParameterJdbcTemplate named;
    private final AuditLogService auditLogService;
    private final AdminAlertRecipients adminAlertRecipients;

    public ApiTokenServiceImpl(NamedParameterJdbcTemplate named, AuditLogService auditLogService,
                               AdminAlertRecipients adminAlertRecipients) {
        this.named = named;
        this.auditLogService = auditLogService;
        this.adminAlertRecipients = adminAlertRecipients;
    }

    @Override
    @Transactional
    public ApiTokenCreated create(Long userId, ApiTokenCreateInput input) {
        if (input == null) {
            throw new ServiceException("Token 创建内容不能为空：请在「API Token」页点击「新建 Token」，"
                    + "在「新建 API Token」弹窗填写名称与权限范围后再提交", BizErrorCode.VALIDATION_ERROR);
        }
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) {
            throw new ServiceException("Token 名称不能为空（列表展示与吊销定位用）：请在「新建 API Token」"
                            + "弹窗的「名称」输入框填写用途标识（示例：CI 回归 Agent）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (name.length() > NAME_MAX) {
            throw new ServiceException("Token 名称最长 " + NAME_MAX + " 字（当前 " + name.length() + " 字）："
                            + "请在「新建 API Token」弹窗的「名称」输入框缩短后重新提交",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (userId == null) {
            throw new ServiceException("Token 属主不能为空：当前登录会话未带出用户身份，请重新登录"
                    + "（右上角头像菜单「退出登录」）后，再到「API Token」页点击「新建 Token」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String normalizedScopes = normalizeScopes(input.scopes());
        String normalizedProjectScope = normalizeProjectScope(input.projectScope());

        String plaintext = PLAINTEXT_PREFIX
                + Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(randomBytes(RANDOM_BYTES));
        String hash = DigestHex.sha256(plaintext);

        Long id = named.queryForObject("""
                INSERT INTO tp_api_token (user_id, name, token_hash, scopes, expires_at, project_scope)
                VALUES (:userId, :name, :hash, :scopes, :expiresAt, :projectScope) RETURNING id
                """, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("name", name)
                .addValue("hash", hash)
                .addValue("scopes", normalizedScopes)
                .addValue("expiresAt", input.expiresAt() == null
                        ? null : Timestamp.valueOf(input.expiresAt()))
                .addValue("projectScope", normalizedProjectScope),
                Long.class);

        Map<String, Object> after = new LinkedHashMap<>();
        after.put("id", id);
        after.put("name", name);
        after.put("scopes", normalizedScopes);
        after.put("fingerprint", hash.substring(0, 8));
        after.put("expiresAt", input.expiresAt() == null ? "永久"
                : ViewFormats.formatTs(Timestamp.valueOf(input.expiresAt())));
        after.put("projectScope", normalizedProjectScope == null ? "不限制（属主成员集）"
                : normalizedProjectScope);
        auditLogService.record(new AuditLogInput(userId, null, OBJECT_TYPE,
                String.valueOf(id), "create", Map.of(), after));
        return new ApiTokenCreated(id, plaintext);
    }

    @Override
    public List<ApiTokenView> listByUser(Long userId) {
        return named.query(
                "SELECT t.id, t.user_id, t.name, t.token_hash, t.scopes, t.expires_at,"
                        + " t.revoked, t.last_used_at, t.project_scope"
                        + " FROM tp_api_token t WHERE t.user_id = :userId ORDER BY t.id ASC",
                new MapSqlParameterSource("userId", userId),
                new BeanPropertyRowMapper<>(ApiTokenRow.class))
                .stream().map(ApiTokenRow::toView).toList();
    }

    @Override
    @Transactional
    public void revoke(Long id, Long operatorId) {
        if (operatorId == null) {
            throw new ServiceException("登录状态已失效，请重新登录后再吊销 Token"
                            + "（重新登录入口在右上角头像菜单「退出登录」），"
                            + "再回到「API Token」列表点击对应行的「吊销」",
                    BizErrorCode.UNAUTHORIZED);
        }
        ApiTokenRow row = findOwnedById(id);
        if (!row.getUserId().equals(operatorId)
                && !adminAlertRecipients.adminUserIds().contains(operatorId)) {
            throw new ServiceException("只能吊销本人的 API Token（该 Token 属主不是当前账号）："
                            + "请在「API Token」列表核对行内属主后吊销本人的 Token，"
                            + "如需代吊销请联系系统管理员",
                    BizErrorCode.FORBIDDEN);
        }
        if (row.getRevoked() != null && row.getRevoked() == 1) {
            return;
        }
        named.update("UPDATE tp_api_token SET revoked = 1, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", id));
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("id", row.getId());
        before.put("name", row.getName());
        before.put("revoked", 0);
        auditLogService.record(new AuditLogInput(operatorId, null, OBJECT_TYPE,
                String.valueOf(id), "revoke", before, Map.of("revoked", true)));
    }

    @Override
    public ApiTokenVerifyResult verify(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return ApiTokenVerifyResult.fail(ApiTokenVerifyStatus.INVALID,
                    "缺少 API Token（请在系统「API Token」页面创建后，以 Bearer 方式携带）");
        }
        ApiTokenRow row = named.query(
                "SELECT t.id, t.user_id, t.name, t.token_hash, t.scopes, t.expires_at,"
                        + " t.revoked, t.last_used_at, t.project_scope,"
                        + " u.status AS owner_status, u.deleted AS owner_deleted"
                        + " FROM tp_api_token t"
                        + " LEFT JOIN tp_user u ON u.id = t.user_id"
                        + " WHERE t.token_hash = :hash",
                new MapSqlParameterSource("hash", DigestHex.sha256(plaintext.trim())),
                new BeanPropertyRowMapper<>(ApiTokenRow.class))
                .stream().findFirst().orElse(null);
        if (row == null) {
            return ApiTokenVerifyResult.fail(ApiTokenVerifyStatus.INVALID,
                    "API Token 无效（未找到该 Token，请确认已完整复制且未被删除）");
        }
        if (row.getRevoked() != null && row.getRevoked() == 1) {
            return ApiTokenVerifyResult.fail(ApiTokenVerifyStatus.REVOKED,
                    "API Token 已被吊销（属主「" + row.getName() + "」），请重新创建");
        }
        if (row.getExpiresAt() != null && row.getExpiresAt().toLocalDateTime().isBefore(LocalDateTime.now())) {
            return ApiTokenVerifyResult.fail(ApiTokenVerifyStatus.EXPIRED,
                    "API Token 已过期（过期时间 " + ViewFormats.formatTs(row.getExpiresAt()) + "），请重新创建");
        }
        if (row.getOwnerStatus() == null || row.getOwnerStatus() != 1
                || (row.getOwnerDeleted() != null && row.getOwnerDeleted() == 1)) {
            return ApiTokenVerifyResult.fail(ApiTokenVerifyStatus.INVALID,
                    "API Token 已失效（属主账号已停用或删除）");
        }
        named.update("UPDATE tp_api_token SET last_used_at = now() WHERE id = :id",
                new MapSqlParameterSource("id", row.getId()));
        return ApiTokenVerifyResult.ok(row.getId(), row.getUserId(),
                parseScopes(row.getScopes()), parseProjectScope(row.getProjectScope()));
    }

 
    private String normalizeScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            throw new ServiceException("Token 权限范围（scope）不能为空：请在「新建 API Token」弹窗勾选至少一项，"
                    + "或填逗号分隔且每项以 " + SCOPE_PREFIX + " 开头或为 " + SCOPE_CI
                    + "（正确写法示例：mcp.case,mcp.read,ci）", BizErrorCode.VALIDATION_ERROR);
        }
        if (scopes.length() > SCOPES_MAX) {
            throw new ServiceException("Token 权限范围（scope）过长，最长 " + SCOPES_MAX
                    + " 字（当前 " + scopes.length() + " 字），请在「新建 API Token」弹窗删减不必要的"
                    + " mcp.* / ci 权限项（格式：逗号分隔，每项以 mcp. 开头或为 ci）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<String> items = Arrays.stream(scopes.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (items.isEmpty()) {
            throw new ServiceException("Token 权限范围（scope）不能为空白：请在「新建 API Token」弹窗直接勾选，"
                    + "或填逗号分隔且每项以 " + SCOPE_PREFIX + " 开头或为 " + SCOPE_CI
                    + "（正确写法示例：mcp.case,mcp.read,ci）", BizErrorCode.VALIDATION_ERROR);
        }
        for (String item : items) {
            boolean mcpScope = item.startsWith(SCOPE_PREFIX) && item.length() > SCOPE_PREFIX.length();
            if (!mcpScope && !SCOPE_CI.equals(item)) {
                throw new ServiceException("Token 权限范围（scope）「" + item
                        + "」非法：请改为每项以 " + SCOPE_PREFIX + " 开头（示例 mcp.case）或为字面 "
                        + SCOPE_CI + "（CI/CD 触发面），可在「新建 API Token」弹窗直接勾选权限项",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
        return String.join(",", items);
    }

    /**
     * GA1 project-pin normalization (rejected pre-DB, same family as the scope
     * checks): null/blank = unrestricted, stored NULL — the gateway then applies
     * only the owner-membership check and pre-GA1 behaviour is unchanged.
     * Otherwise comma-separated numeric project ids, trimmed, de-duplicated,
     * ascending; total width capped at the column limit.
     */
    private String normalizeProjectScope(String projectScope) {
        if (projectScope == null || projectScope.isBlank()) {
            return null;
        }
        java.util.TreeSet<Long> ids = new java.util.TreeSet<>();
        for (String item : Arrays.stream(projectScope.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList()) {
            if (!item.matches("\\d{1,19}")) {
                throw new ServiceException("Token 项目范围只能填逗号分隔的项目 ID 数字"
                        + "（「" + item + "」不是有效的项目 ID）：请在「新建 API Token」弹窗的"
                        + "「受限项目」下拉中直接勾选项目，无需手填 ID", BizErrorCode.VALIDATION_ERROR);
            }
            ids.add(Long.parseLong(item));
        }
        if (ids.isEmpty()) {
            return null;
        }
        String normalized = ids.stream().map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        if (normalized.length() > PROJECT_SCOPE_MAX) {
            throw new ServiceException("Token 项目范围过长，最长 " + PROJECT_SCOPE_MAX
                    + " 字（当前 " + normalized.length() + " 字），请在「新建 API Token」弹窗的"
                    + "「受限项目」下拉中减少勾选的项目数量",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    /** Normalized project_scope to id list (verify OK output; empty = unrestricted). */
    private List<String> parseProjectScope(String projectScope) {
        if (projectScope == null || projectScope.isBlank()) {
            return List.of();
        }
        return Arrays.stream(projectScope.split(",")).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    
    private List<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(scopes.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private ApiTokenRow findOwnedById(Long id) {
        if (id == null) {
            throw new ServiceException("Token ID 不能为空：请从「API Token」列表对应行的「吊销」按钮发起操作",
                    BizErrorCode.VALIDATION_ERROR);
        }
        ApiTokenRow row = named.query(
                "SELECT t.id, t.user_id, t.name, t.token_hash, t.scopes, t.expires_at,"
                        + " t.revoked, t.last_used_at"
                        + " FROM tp_api_token t WHERE t.id = :id",
                new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(ApiTokenRow.class))
                .stream().findFirst().orElse(null);
        if (row == null) {
            throw new ServiceException("API Token 不存在（id=" + id + "）：该 Token 可能已被删除，"
                    + "请回到「API Token」列表核对指纹与名称后，点击对应行的「吊销」重试",
                    BizErrorCode.NOT_FOUND);
        }
        return row;
    }

    private static byte[] randomBytes(int len) {
        byte[] buf = new byte[len];
        SECURE_RANDOM.nextBytes(buf);
        return buf;
    }
}
