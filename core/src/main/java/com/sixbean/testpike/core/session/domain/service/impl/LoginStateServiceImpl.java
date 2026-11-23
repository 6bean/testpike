package com.sixbean.testpike.core.session.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.credential.api.dto.CredentialInfo;
import com.sixbean.testpike.core.credential.api.dto.CredentialInput;
import com.sixbean.testpike.core.credential.domain.service.CredentialService;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.session.api.dto.ExtraAuthRef;
import com.sixbean.testpike.core.session.api.dto.LoginStateInfo;
import com.sixbean.testpike.core.session.api.dto.LoginStateInput;
import com.sixbean.testpike.core.session.domain.component.SessionStateSupport;
import com.sixbean.testpike.core.session.domain.service.LoginStateService;
import com.sixbean.testpike.core.session.infrastructure.dataobject.LoginState;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Login-state ledger implementation (NamedParameterJdbcTemplate base path,
 * same idiom as the credential vault).
 *
 * <p>Register = vault upsert + ledger upsert in one transaction. The captured
 * secret is encrypted through CredentialService (never a second vault); the
 * ledger row stores provenance (source_kind), a parsed expiry when parseable
 * (honest NULL otherwise) and the jsonb origin whitelist. Same-name re-capture
 * renews the row in place (D9 update-never-append). Vault rows written through
 * this service are stamped source_kind=3 (browser capture) so the lifecycle
 * columns carry real meaning; legacy rows stay NULL (zero drift).
 */
@Service
public class LoginStateServiceImpl implements LoginStateService {

    /** Provenance stamp for rows registered through the capture ledger path. */
    private static final int SOURCE_CAPTURE = 3;

    /** Hot cap on combined-session headers one login state may carry . */
    static final String EXTRA_REFS_CAP_KEY = "session.state.extra-auth-max-refs";
    static final int EXTRA_REFS_CAP_DEFAULT = 8;

    private final NamedParameterJdbcTemplate jdbc;
    private final CredentialService credentialService;
    private final SessionStateSupport support;
    private final ConfigService configService;

    public LoginStateServiceImpl(NamedParameterJdbcTemplate jdbc,
                                 CredentialService credentialService,
                                 SessionStateSupport support,
                                 ConfigService configService) {
        this.jdbc = jdbc;
        this.credentialService = credentialService;
        this.support = support;
        this.configService = configService;
    }

    @Override
    @Transactional
    public long register(long projectId, LoginStateInput input) {
        validate(input);
        LocalDateTime expiresAt = support.parseExpiresAt(input.kind(), input.secret());
        long credentialId = upsertVaultCredential(projectId, input, expiresAt);
        // After the vault upsert so an extra ref may point at the primary
        // credKey this very register call just created (first registration).
        assertExtraRefsRegistered(projectId, input.extraAuthRefs());
        LoginState existing = findByName(projectId, input.stateName());
        long id = existing == null
                ? insertRow(projectId, input, credentialId, expiresAt)
                : updateRow(existing.getId(), input, credentialId, expiresAt);
        return id;
    }

    @Override
    @Transactional
    public void update(long id, LoginStateInput input) {
        LoginState row = requireRow(id);
        long projectId = row.getProjectId();
        // Structural gate first, then the merged-view gate: the combined
        // headers actually being stored (supplied list, or the stored list
        // kept because no list came in) must not clash with the NEW kind's
        // primary header — a direct API caller who flips the kind without a
        // list would otherwise land a self-overwriting pair.
        List<String> problems = new ArrayList<>();
        validateIdentity(input, problems);
        List<ExtraAuthRef> storedRefs = ExtraAuthRef.readJson(row.getExtraAuthRefsJson());
        List<ExtraAuthRef> refs = input.extraAuthRefs() == null ? storedRefs : input.extraAuthRefs();
        validateExtraRefs(input.kind(), refs, problems);
        if (!problems.isEmpty()) {
            throw new ServiceException(String.join("；", problems), BizErrorCode.VALIDATION_ERROR);
        }
        // Same save-time material-kind gate as the register door.
        support.assertMaterialMatchesKind(input.kind(), input.stateName(), input.secret());
        boolean renamed = !input.stateName().equals(row.getStateName());
        if (renamed && findByName(projectId, input.stateName()) != null) {
            throw new ServiceException("登录态「" + input.stateName()
                    + "」在本项目已有另一行——「登录态名称」项目内必须唯一，用例与探索都按名称引用登录态；"
                    + "请在「登录态名称」输入框改成其它名称，或关闭本窗口后到「造数与资产 → 登录态账本」列表编辑已存在的那一行",
                    BizErrorCode.VALIDATION_ERROR);
        }
        boolean secretSupplied = input.secret() != null && !input.secret().isBlank();
        String storedCredKey = credKeyOf(row.getCredentialId());
        boolean credRelink = !input.credKey().equals(storedCredKey);
        if (!secretSupplied && credRelink && findCredentialByKey(projectId, input.credKey()) == null) {
            throw new ServiceException("凭据「" + input.credKey() + "」在本项目还未建档——"
                    + "请先在「凭据保险箱」页面登记该凭据，或填写凭据值随本次保存一并入保险库",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Edit-time expiry honesty: only freshly supplied material is parsed
        // again; without new material the stored expiry stays as it was.
        LocalDateTime expiresAt = secretSupplied
                ? support.parseExpiresAt(input.kind(), input.secret())
                : row.getExpiresAt();
        long credentialId = editVaultMaterial(projectId, input, secretSupplied, expiresAt);
        assertExtraRefsRegistered(projectId, refs);
        String refsJson = ExtraAuthRef.writeJson(refs);
        if (unchanged(row, input, storedCredKey, refsJson, secretSupplied)) {
            // R28 idempotency: an edit that changes nothing writes nothing —
            // no updated_at churn, no verification-stamp drop, no audit noise.
            return;
        }
        boolean materialChanged = secretSupplied || credRelink
                || !input.kind().equals(row.getKind())
                || !refsJson.equals(ExtraAuthRef.writeJson(storedRefs));
        try {
            jdbc.update("UPDATE tp_login_state SET state_name = :name, role_scope = :role,"
                            + " account_label = :label, kind = :kind, credential_id = :cred,"
                            + " state_object_key = COALESCE(:obj, state_object_key),"
                            + " scope_origins = :origins::jsonb, extra_auth_refs = :refs::jsonb,"
                            + " expires_at = :exp,"
                            + " last_verified_at = CASE WHEN :drop THEN NULL ELSE last_verified_at END,"
                            + " remark = :remark, updated_at = now() WHERE id = :id",
                    ledgerParams(input, credentialId, expiresAt)
                            .addValue("refs", refsJson, java.sql.Types.OTHER)
                            .addValue("drop", materialChanged)
                            .addValue("id", id));
        } catch (DuplicateKeyException raced) {
            // uk(project_id, state_name): a concurrent rename took the name.
            throw new ServiceException("登录态「" + input.stateName()
                    + "」在本项目已有另一行——「登录态名称」项目内必须唯一，用例与探索都按名称引用登录态；"
                    + "请在「登录态名称」输入框改成其它名称，或关闭本窗口后到「造数与资产 → 登录态账本」列表编辑已存在的那一行",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /**
     * Vault write for the edit door: blank secret never touches the vault
     * (keep-the-current semantics), fresh secret updates the ciphertext
     * through the single CredentialService path and re-stamps the parsed
     * expiry. Unlike the capture path this door never rewrites source_kind —
     * provenance recorded at capture time stays truthful.
     */
    private long editVaultMaterial(long projectId, LoginStateInput input,
                                   boolean secretSupplied, LocalDateTime expiresAt) {
        CredentialInfo existing = findCredentialByKey(projectId, input.credKey());
        if (existing == null) {
            return credentialService.create(projectId, new CredentialInput(
                    input.credKey(), input.credKey(), input.secret(), input.remark()));
        }
        if (secretSupplied) {
            credentialService.update(existing.id(), new CredentialInput(
                    existing.name(), input.credKey(), input.secret(), input.remark()));
            if (expiresAt != null) {
                jdbc.update("UPDATE tp_credential SET expires_at = :exp WHERE id = :id",
                        new MapSqlParameterSource()
                                .addValue("exp", expiresAt, java.sql.Types.TIMESTAMP)
                                .addValue("id", existing.id()));
            }
        }
        return existing.id();
    }

    /** True when the edit carries no field change at all (R28 no-op save). */
    private boolean unchanged(LoginState row, LoginStateInput input, String storedCredKey,
                              String refsJson, boolean secretSupplied) {
        if (secretSupplied) {
            return false;
        }
        return eq(row.getStateName(), input.stateName())
                && eq(row.getKind(), input.kind())
                && eq(blankToNull(row.getRoleScope()), blankToNull(input.roleScope()))
                && eq(blankToNull(row.getAccountLabel()), blankToNull(input.accountLabel()))
                && eq(blankToNull(row.getRemark()), blankToNull(input.remark()))
                && eq(storedCredKey, input.credKey())
                && eq(refsJson, ExtraAuthRef.writeJson(ExtraAuthRef.readJson(row.getExtraAuthRefsJson())))
                && eq(row.originsAsList(), input.scopeOrigins() == null ? List.of() : input.scopeOrigins());
    }

    private static boolean eq(Object a, Object b) {
        return java.util.Objects.equals(a, b);
    }

    private LoginState requireRow(long id) {
        LoginState row = findById(id);
        if (row == null) {
            throw new ServiceException(
                    "登录态 #" + id + " 不存在——这一行可能已被删除；"
                            + "请刷新「造数与资产 → 登录态账本」列表确认对象后，再对存在的行发起操作。",
                    BizErrorCode.NOT_FOUND);
        }
        return row;
    }

    @Override
    public LoginStateInfo get(long id) {
        return toInfo(requireRow(id));
    }

    @Override
    public List<LoginStateInfo> list(long projectId, Boolean enabledOnly) {
        // Delegation keeps ONE query source (R40): the legacy full-list door and
        // the paged management door read the same predicate, the legacy door
        // simply asks for the first cap-sized page without filters.
        return listPage(projectId, null, Boolean.TRUE.equals(enabledOnly) ? 1 : null,
                DEFAULT_SORT_KEY, Boolean.TRUE, 1, PageQueryPolicy.MAX_REQUEST_SIZE).list();
    }

    /** Ledger column the unsorted reads order by; also the tie-breaker. */
    private static final String DEFAULT_SORT_KEY = "stateName";

    /**
     * Sort whitelist: UI sort key -> ledger column. Keys outside this map are
     * refused (see {@link #orderClause}): a derived value such as the
     * four-state light or the vault-side reference key has no ledger column,
     * and re-ordering only the loaded pageful would pretend to be a global sort.
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "stateName", "l.state_name",
            "kind", "l.kind",
            "roleScope", "l.role_scope",
            "accountLabel", "l.account_label",
            "expiresAt", "l.expires_at",
            "lastVerifiedAt", "l.last_verified_at",
            "createdAt", "l.created_at",
            "updatedAt", "l.updated_at",
            "status", "l.status");

    @Override
    public PageResult<LoginStateInfo> listPage(long projectId, String keyword, Integer status,
                                               String sortKey, Boolean sortAsc, int page, int size) {
        int safePage = Math.max(page, 1);
        int safeSize = PageQueryPolicy.enforceSize(size);
        String order = orderClause(sortKey, sortAsc);

        MapSqlParameterSource ps = new MapSqlParameterSource("pid", projectId)
                .addValue("limit", safeSize)
                .addValue("offset", (long) (safePage - 1) * safeSize);
        StringBuilder where = new StringBuilder(" WHERE l.project_id = :pid");
        if (status != null) {
            where.append(" AND l.status = :status");
            ps.addValue("status", status);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (l.state_name ILIKE :kw OR l.account_label ILIKE :kw)");
            ps.addValue("kw", "%" + keyword.trim() + "%");
        }
        long total = jdbc.queryForObject("SELECT count(*) FROM tp_login_state l" + where, ps, Long.class);
        List<LoginStateInfo> rows = jdbc.query(
                "SELECT " + LoginState.SELECT_COLUMNS + " FROM tp_login_state l" + where
                        + " ORDER BY " + order + " LIMIT :limit OFFSET :offset",
                ps, LoginState.ROW_MAPPER).stream().map(this::toInfo).toList();
        return new PageResult<>(rows, total);
    }

    private String orderClause(String sortKey, Boolean sortAsc) {
        String column = sortKey == null || sortKey.isBlank()
                ? SORT_COLUMNS.get(DEFAULT_SORT_KEY)
                : SORT_COLUMNS.get(sortKey);
        if (column == null) {
            throw new ServiceException(
                    "登录态账本不支持按「" + sortKey + "」排序——该列不是账本字段（状态灯由启用状态与有效期推导，"
                            + "引用键在保险库侧）。可按名称、类型、角色范围、账号、有效期、核验时间或启用状态排序。",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String direction = Boolean.FALSE.equals(sortAsc) ? "DESC" : "ASC";
        // Nulls last in both directions keeps unparseable/absent values out of
        // the head of the list, matching the loaded-page behaviour the page had.
        return column + " " + direction + " NULLS LAST"
                + (DEFAULT_SORT_KEY.equals(sortKey) || sortKey == null || sortKey.isBlank()
                        ? "" : ", l.state_name ASC");
    }

    @Override
    public void setEnabled(long id, boolean enabled) {
        int updated = jdbc.update("UPDATE tp_login_state SET status = :st, updated_at = now()"
                        + " WHERE id = :id",
                new MapSqlParameterSource("st", enabled ? 1 : 0).addValue("id", id));
        if (updated == 0) {
            throw new ServiceException(
                    "登录态 #" + id + " 不存在，无法调整启用状态。", BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public void markVerified(long id, boolean valid) {
        markVerified(id, valid, null);
    }

    @Override
    public void markVerified(long id, boolean valid, String reason) {
        LoginState row = findById(id);
        // Single UPDATE path: COALESCE keeps remark verbatim when no reason is
        // supplied, so the two-arg behaviour is byte-identical to before.
        MapSqlParameterSource params = new MapSqlParameterSource("id", id)
                .addValue("ok", valid)
                .addValue("rk", reason == null || reason.isBlank() ? null : reason);
        int updated = jdbc.update("UPDATE tp_login_state SET last_verified_at = now(),"
                        + " status = CASE WHEN :ok THEN status ELSE 0 END,"
                        + " remark = COALESCE(:rk, remark), updated_at = now()"
                        + " WHERE id = :id",
                params);
        if (updated == 0) {
            throw new ServiceException(
                    "登录态 #" + id + " 不存在，无法记录核验结果。", BizErrorCode.NOT_FOUND);
        }
        if (row.getCredentialId() != null) {
            jdbc.update("UPDATE tp_credential SET last_verified_at = now() WHERE id = :cid",
                    new MapSqlParameterSource("cid", row.getCredentialId()));
        }
    }

    @Override
    public void delete(long id) {
        int updated = jdbc.update("DELETE FROM tp_login_state WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (updated == 0) {
            throw new ServiceException(
                    "登录态 #" + id + " 不存在，无需删除。", BizErrorCode.NOT_FOUND);
        }
    }

    private void validate(LoginStateInput input) {
        List<String> problems = new ArrayList<>();
        validateIdentity(input, problems);
        validateExtraRefs(input.kind(), input.extraAuthRefs(), problems);
        if (!problems.isEmpty()) {
            throw new ServiceException(String.join("；", problems), BizErrorCode.VALIDATION_ERROR);
        }
        // Material-kind consistency (11-19 report gate): supplied material
        // must replay under the declared kind; refuse at save time instead of
        // letting the failure surface only when the row is replay-tested.
        support.assertMaterialMatchesKind(input.kind(), input.stateName(), input.secret());
    }

    /** Identity/value field gate shared by the register and edit doors (R40). */
    private void validateIdentity(LoginStateInput input, List<String> problems) {
        if (input.stateName() == null || input.stateName().isBlank()) {
            problems.add("状态名称必填（项目内唯一，重新采集同名即更新该行）");
        } else if (input.stateName().length() > 200) {
            problems.add("状态名称不能超过 200 字");
        }
        if (!SessionStateSupport.KINDS.contains(input.kind())) {
            problems.add("登录态类型只能是 token / storage_state / cookie_set，收到：" + input.kind());
        }
        if (input.credKey() == null || input.credKey().isBlank()) {
            problems.add("凭据引用键必填（保险库单源，账本行不落明文）");
        }
    }

    /**
 * Structural checks for the combined-session header list ,
 * fail-closed at save time: into=header only, header name required and
 * neither clashing with the kind's primary header nor repeated inside
 * the list, valueTemplate either absent or referencing the vault
 * ({{CRED:key}} prefix grammar — a template without a reference would
 * hide plaintext on the ledger row), list size under the hot cap.
 */
    private void validateExtraRefs(String kind, List<ExtraAuthRef> supplied, List<String> problems) {
        List<ExtraAuthRef> refs = supplied == null ? List.of() : supplied;
        int cap = intConfig(EXTRA_REFS_CAP_KEY, EXTRA_REFS_CAP_DEFAULT);
        if (refs.size() > cap) {
            problems.add("附加会话头最多 " + cap + " 条（参数 session.state.extra-auth-max-refs，"
                    + "可在「系统设置-系统配置」调整），本次收到 " + refs.size()
                    + " 条——请合并或删除多余条目");
        }
        String mainHeader = SessionStateSupport.mainHeaderNameOfKind(kind);
        boolean kindKnown = SessionStateSupport.KINDS.contains(kind);
        Set<String> seenHeaders = new HashSet<>();
        for (ExtraAuthRef ref : refs) {
            if (ref.credKey() == null || ref.credKey().isBlank()) {
                problems.add("附加会话头必须选择引用凭据（引用键指向「凭据保险箱」，账本行不落明文值）");
            }
            if (!ExtraAuthRef.INTO_HEADER.equalsIgnoreCase(ref.into())) {
                problems.add("附加会话头的送达位置只能是 header，收到：" + ref.into()
                        + "——请在「附加会话头」区选择「请求头」");
            }
            String header = ref.headerName() == null ? "" : ref.headerName().strip();
            if (header.isEmpty()) {
                problems.add("附加会话头必须填写头名称（例如 X-API-KEY）");
            } else {
                String lower = header.toLowerCase();
                if (!seenHeaders.add(lower)) {
                    problems.add("附加会话头「" + header + "」在清单里出现多次——同一请求头只允许一行，请合并");
                }
                if (kindKnown && lower.equalsIgnoreCase(mainHeader)) {
                    problems.add("附加会话头「" + header + "」与登录态类型 " + kind
                            + " 自带的主请求头同名，会互相覆盖——请改用其它头名，"
                            + "或把该凭据登记为登录态的主凭据引用键");
                }
            }
            if (ref.valueTemplate() != null && !ref.valueTemplate().isBlank()
                    && !ref.valueTemplate().contains(ExtraAuthRef.CRED_PLACEHOLDER_PREFIX)) {
                problems.add("附加会话头「" + header + "」的值模板「" + ref.valueTemplate()
                        + "」没有引用任何凭据——合法形态例如 Bearer {{CRED:api_key}}；"
                        + "明文值请先存入「凭据保险箱」，再用引用键拼模板");
            }
        }
    }

    /**
     * Existence checks (run after the vault upsert): every extra ref must
     * point at a credential already registered in this project — refs are
     * never created implicitly, an unknown key is named with its remedy door.
     */
    private void assertExtraRefsRegistered(long projectId, List<ExtraAuthRef> refs) {
        if (refs == null || refs.isEmpty()) {
            return;
        }
        Set<String> vaultKeys = credentialService.list(projectId, null).stream()
                .map(CredentialInfo::credKey)
                .collect(Collectors.toSet());
        List<String> missing = new ArrayList<>();
        for (ExtraAuthRef ref : refs) {
            String key = ref.credKey() == null ? "" : ref.credKey().strip();
            if (!key.isEmpty() && !vaultKeys.contains(key) && !missing.contains(key)) {
                missing.add(key);
            }
        }
        if (!missing.isEmpty()) {
            throw new ServiceException("附加会话头引用的凭据「" + String.join("、", missing)
                    + "」在本项目还未建档——请先在「凭据保险箱」页面登记这些凭据，再保存登录态",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private int intConfig(String key, int fallback) {
        try {
            return Math.max(1, Integer.parseInt(configService.getValue(key, String.valueOf(fallback)).strip()));
        } catch (RuntimeException malformed) {
            return fallback;
        }
    }

    private long upsertVaultCredential(long projectId, LoginStateInput input, LocalDateTime expiresAt) {
        CredentialInfo existing = findCredentialByKey(projectId, input.credKey());
        long credentialId;
        if (existing == null) {
            if (input.secret() == null || input.secret().isBlank()) {
                throw new ServiceException(
                        "凭据「" + input.credKey() + "」在本项目还未建档，采集登记必须携带本次获取到的值。",
                        BizErrorCode.VALIDATION_ERROR);
            }
            credentialId = credentialService.create(projectId, new CredentialInput(
                    input.credKey(), input.credKey(), input.secret(), input.remark()));
        } else {
            credentialId = existing.id();
            if (input.secret() != null && !input.secret().isBlank()) {
                credentialService.update(credentialId, new CredentialInput(
                        existing.name(), input.credKey(), input.secret(), input.remark()));
            }
        }
        // Postgres cannot infer a NULL parameter's type inside CASE WHEN ? IS NULL,
        // so the "keep previous expiry when unparseable" branch is split in Java.
        if (expiresAt == null) {
            jdbc.update("UPDATE tp_credential SET source_kind = :sk WHERE id = :id",
                    new MapSqlParameterSource("sk", SOURCE_CAPTURE).addValue("id", credentialId));
        } else {
            jdbc.update("UPDATE tp_credential SET source_kind = :sk, expires_at = :exp WHERE id = :id",
                    new MapSqlParameterSource("sk", SOURCE_CAPTURE)
                            .addValue("exp", expiresAt).addValue("id", credentialId));
        }
        return credentialId;
    }

    private CredentialInfo findCredentialByKey(long projectId, String credKey) {
        return credentialService.list(projectId, null).stream()
                .filter(c -> credKey.equals(c.credKey()))
                .findFirst().orElse(null);
    }

    private LoginState findByName(long projectId, String stateName) {
        List<LoginState> rows = jdbc.query(
                "SELECT " + LoginState.SELECT_COLUMNS + " FROM tp_login_state l"
                        + " WHERE l.project_id = :pid AND l.state_name = :name",
                new MapSqlParameterSource("pid", projectId).addValue("name", stateName),
                LoginState.ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private LoginState findById(long id) {
        List<LoginState> rows = jdbc.query(
                "SELECT " + LoginState.SELECT_COLUMNS + " FROM tp_login_state l WHERE l.id = :id",
                new MapSqlParameterSource("id", id), LoginState.ROW_MAPPER);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private long insertRow(long projectId, LoginStateInput input, long credentialId,
                           LocalDateTime expiresAt) {
        try {
            jdbc.update("INSERT INTO tp_login_state (project_id, state_name, role_scope,"
                            + " account_label, kind, credential_id, state_object_key,"
                            + " scope_origins, extra_auth_refs, source_kind, expires_at, remark)"
                            + " VALUES (:pid, :name, :role, :label, :kind, :cred, :obj,"
                            + " :origins::jsonb, COALESCE(:refs::jsonb, '[]'::jsonb), :sk, :exp, :remark)",
                    ledgerParams(input, credentialId, expiresAt).addValue("pid", projectId));
        } catch (DuplicateKeyException raced) {
            // uk(project_id, state_name): a concurrent capture renewed the same row —
            // fall through to the update path so re-capture keeps update-never-append.
            LoginState row = findByName(projectId, input.stateName());
            if (row == null) {
                throw raced;
            }
            return updateRow(row.getId(), input, credentialId, expiresAt);
        }
        return findByName(projectId, input.stateName()).getId();
    }

    private long updateRow(long id, LoginStateInput input, long credentialId,
                           LocalDateTime expiresAt) {
        jdbc.update("UPDATE tp_login_state SET role_scope = :role, account_label = :label,"
                        + " kind = :kind, credential_id = :cred, state_object_key = :obj,"
                        + " scope_origins = :origins::jsonb,"
                        + " extra_auth_refs = COALESCE(:refs::jsonb, extra_auth_refs), source_kind = :sk,"
                        + " expires_at = :exp, last_verified_at = NULL, status = 1,"
                        + " remark = :remark, updated_at = now() WHERE id = :id",
                ledgerParams(input, credentialId, expiresAt)
                        .addValue("id", id));
        return id;
    }

    private MapSqlParameterSource ledgerParams(LoginStateInput input, long credentialId,
                                               LocalDateTime expiresAt) {
        String origins = toJsonArray(input.scopeOrigins());
        return new MapSqlParameterSource()
                .addValue("name", input.stateName())
                .addValue("role", blankToNull(input.roleScope()))
                .addValue("label", blankToNull(input.accountLabel()))
                .addValue("kind", input.kind())
                .addValue("cred", credentialId)
                .addValue("obj", blankToNull(input.stateObjectKey()))
                .addValue("origins", origins)
                // null = "no list supplied" (legacy capture doors): the write
                // statements COALESCE it away; an explicit list — including an
                // empty one from the ledger dialog — replaces the stored refs.
                .addValue("refs", input.extraAuthRefs() == null
                        ? null : ExtraAuthRef.writeJson(input.extraAuthRefs()), java.sql.Types.OTHER)
                .addValue("sk", SOURCE_CAPTURE)
                .addValue("exp", expiresAt, java.sql.Types.TIMESTAMP)
                .addValue("remark", blankToNull(input.remark()));
    }

    private String toJsonArray(List<String> origins) {
        StringBuilder sb = new StringBuilder("[");
        List<String> values = origins == null ? List.of() : origins;
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return sb.append(']').toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private LoginStateInfo toInfo(LoginState row) {
        return new LoginStateInfo(row.getId(), row.getProjectId(), row.getStateName(),
                row.getRoleScope(), row.getAccountLabel(), row.getKind(),
                row.getCredentialId(), credKeyOf(row.getCredentialId()), row.getStateObjectKey(),
                row.originsAsList(), row.getSourceKind(), row.getExpiresAt(),
                row.getLastVerifiedAt(), row.getStatus(), row.getRemark(),
                row.getCreatedAt(), row.getUpdatedAt(),
                ExtraAuthRef.readJson(row.getExtraAuthRefsJson()));
    }

    /** Vault reference key behind a credential id (null id or missing row = null). */
    private String credKeyOf(Long credentialId) {
        if (credentialId == null) {
            return null;
        }
        List<String> keys = jdbc.query(
                "SELECT cred_key FROM tp_credential WHERE id = :id",
                new MapSqlParameterSource("id", credentialId),
                (rs, n) -> rs.getString(1));
        return keys.isEmpty() ? null : keys.get(0);
    }
}
