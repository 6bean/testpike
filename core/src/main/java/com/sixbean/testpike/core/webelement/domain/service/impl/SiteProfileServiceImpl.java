package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileInput;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.component.SiteScopeRules;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Site profile CRUD on tp_site_profile (NamedParameterJdbcTemplate direct SQL,
 * jsonb columns written with CAST and read back as text). Rules carried here:
 * the scope prefix is derived from startUrl on the server only; name is unique
 * inside one project and the refusal names the colliding profile; an update
 * whose values equal the stored ones performs zero writes (revision and
 * updated_at stay frozen); a write carrying a stale revision token is refused;
 * deleting removes only the profile row - survey runs and collected elements
 * outlive it.
 */
@Service
public class SiteProfileServiceImpl implements SiteProfileService {

    /** Preset ignored query params; mirrors the shipped seed of the same key. */
    static final String KEY_DEFAULT_IGNORE_PARAMS = "element.survey-site.default-ignore-params";
    static final List<String> SHIPPED_IGNORE_PARAMS = List.of(
            "page", "pageNum", "pageNo", "size", "limit", "offset", "timestamp", "_t", "rand");

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, name, start_url, scope_prefix,
                   exclude_globs::text AS exclude_globs,
                   ignore_params::text AS ignore_params,
                   verbs_guard_enabled,
                   budget_overrides::text AS budget_overrides,
                   login_state_name, enabled, revision, updated_at
              FROM tp_site_profile
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ConfigService configService;

    public SiteProfileServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper mapper,
                                  ConfigService configService) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.configService = configService;
    }

    @Override
    public SiteProfileView create(SiteProfileInput input) {
        long projectId = requireProject(input);
        String name = requireName(input.name());
        String startUrl = requireStartUrl(input.startUrl());
        String scope = SiteScopeRules.deriveScope(startUrl);
        refuseDuplicateName(projectId, name, null);

        MapSqlParameterSource params = writeParams(input, projectId, name, startUrl, scope);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO tp_site_profile (project_id, name, start_url, scope_prefix,
                    exclude_globs, ignore_params, verbs_guard_enabled, budget_overrides,
                    login_state_name, enabled, revision)
                VALUES (:projectId, :name, :startUrl, :scope,
                    CAST(:excludeGlobs AS jsonb), CAST(:ignoreParams AS jsonb), :verbsGuard,
                    CAST(:budgetOverrides AS jsonb), :loginStateName, :enabled, 0)
                """, params, keyHolder, new String[]{"id"});
        return get(keyHolder.getKey().longValue());
    }

    @Override
    public SiteProfileView update(long id, SiteProfileInput input) {
        SiteProfileView current = get(id);
        if (input.revision() == null || input.revision() != current.revision()) {
            throw refusedStale(current);
        }
        long projectId = input.projectId() != null ? input.projectId() : current.projectId();
        String name = requireName(input.name() != null ? input.name() : current.name());
        String startUrl = requireStartUrl(
                input.startUrl() != null ? input.startUrl() : current.startUrl());
        String scope = SiteScopeRules.deriveScope(startUrl);
        refuseDuplicateName(projectId, name, id);

        List<String> excludeGlobs = input.excludeGlobs() != null
                ? List.copyOf(input.excludeGlobs()) : current.excludeGlobs();
        List<String> ignoreParams = input.ignoreParams() != null
                ? List.copyOf(input.ignoreParams()) : effectiveIgnoreParams();
        boolean verbsGuard = input.verbsGuardEnabled() != null
                ? input.verbsGuardEnabled() : current.verbsGuardEnabled();
        Map<String, Object> budget = input.budgetOverrides() != null
                ? Map.copyOf(input.budgetOverrides()) : current.budgetOverrides();
        String loginState = input.loginStateName() != null
                ? blankToNull(input.loginStateName()) : current.loginStateName();
        boolean enabled = input.enabled() != null ? input.enabled() : current.enabled();

        if (unchanged(current, projectId, name, startUrl, scope, excludeGlobs, ignoreParams,
                verbsGuard, budget, loginState, enabled)) {
            return current; // empty save: zero writes, revision and timestamps frozen
        }

        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("rev", current.revision())
                .addValue("projectId", projectId)
                .addValue("name", name)
                .addValue("startUrl", startUrl)
                .addValue("scope", scope)
                .addValue("excludeGlobs", json(excludeGlobs))
                .addValue("ignoreParams", json(ignoreParams))
                .addValue("verbsGuard", verbsGuard)
                .addValue("budgetOverrides", json(budget))
                .addValue("loginStateName", loginState)
                .addValue("enabled", enabled);
        int touched = jdbc.update("""
                UPDATE tp_site_profile
                   SET project_id = :projectId, name = :name, start_url = :startUrl,
                       scope_prefix = :scope,
                       exclude_globs = CAST(:excludeGlobs AS jsonb),
                       ignore_params = CAST(:ignoreParams AS jsonb),
                       verbs_guard_enabled = :verbsGuard,
                       budget_overrides = CAST(:budgetOverrides AS jsonb),
                       login_state_name = :loginStateName,
                       enabled = :enabled,
                       revision = revision + 1, updated_at = now()
                 WHERE id = :id AND revision = :rev
                """, params);
        if (touched == 0) {
            throw refusedStale(get(id)); // row slipped between the token check and the CAS
        }
        return get(id);
    }

    @Override
    public SiteProfileView get(long id) {
        List<SiteProfileView> rows = jdbc.query(SELECT_COLUMNS + " WHERE id = :id",
                Map.of("id", id), this::mapRow);
        if (rows.isEmpty()) {
            throw new ServiceException("站点档案不存在（可能已被删除），请刷新「站点档案」抽屉确认；需要重新测绘时可在「页面元素库」页重新新建档案",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public List<SiteProfileView> list(long projectId) {
        return jdbc.query(SELECT_COLUMNS + " WHERE project_id = :pid ORDER BY updated_at DESC, id DESC",
                Map.of("pid", projectId), this::mapRow);
    }

    @Override
    public void delete(long id) {
        SiteProfileView current = get(id);
        jdbc.update("DELETE FROM tp_site_profile WHERE id = :id AND revision = :rev",
                Map.of("id", id, "rev", current.revision()));
        // Run ledger and collected elements stay on purpose: they outlive the profile row.
    }

    // ---------- helpers ----------

    private MapSqlParameterSource writeParams(SiteProfileInput input, long projectId,
                                              String name, String startUrl, String scope) {
        List<String> excludeGlobs = input.excludeGlobs() == null
                ? List.of() : List.copyOf(input.excludeGlobs());
        List<String> ignoreParams = input.ignoreParams() == null
                ? effectiveIgnoreParams() : List.copyOf(input.ignoreParams());
        boolean verbsGuard = input.verbsGuardEnabled() == null || input.verbsGuardEnabled();
        Map<String, Object> budget = input.budgetOverrides() == null
                ? Map.of() : Map.copyOf(input.budgetOverrides());
        boolean enabled = input.enabled() == null || input.enabled();
        return new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("name", name)
                .addValue("startUrl", startUrl)
                .addValue("scope", scope)
                .addValue("excludeGlobs", json(excludeGlobs))
                .addValue("ignoreParams", json(ignoreParams))
                .addValue("verbsGuard", verbsGuard)
                .addValue("budgetOverrides", json(budget))
                .addValue("loginStateName", blankToNull(input.loginStateName()))
                .addValue("enabled", enabled);
    }

    private boolean unchanged(SiteProfileView current, long projectId, String name,
                              String startUrl, String scope, List<String> excludeGlobs,
                              List<String> ignoreParams, boolean verbsGuard,
                              Map<String, Object> budget, String loginState, boolean enabled) {
        return Objects.equals(current.projectId(), projectId)
                && Objects.equals(current.name(), name)
                && Objects.equals(current.startUrl(), startUrl)
                && Objects.equals(current.scopePrefix(), scope)
                && Objects.equals(current.excludeGlobs(), excludeGlobs)
                && Objects.equals(current.ignoreParams(), ignoreParams)
                && current.verbsGuardEnabled() == verbsGuard
                && Objects.equals(current.budgetOverrides(), budget)
                && Objects.equals(current.loginStateName(), loginState)
                && current.enabled() == enabled;
    }

    /** Effective ignored-param preset: the hot-updatable stored list, degraded to the shipped one. */
    private List<String> effectiveIgnoreParams() {
        String stored = configService.getValue(KEY_DEFAULT_IGNORE_PARAMS, json(SHIPPED_IGNORE_PARAMS));
        List<String> parsed = readStringList(stored);
        return parsed.isEmpty() ? SHIPPED_IGNORE_PARAMS : parsed;
    }

    private void refuseDuplicateName(long projectId, String name, Long keepId) {
        Integer hits = jdbc.queryForObject("""
                SELECT count(*) FROM tp_site_profile
                 WHERE project_id = :pid AND name = :name AND (:keepId IS NULL OR id <> :keepId)
                """, Map.of("pid", projectId, "name", name, "keepId", keepId == null ? 0L : keepId),
                Integer.class);
        if (hits != null && hits > 0) {
            throw new ServiceException("本项目里已有同名站点档案「%s」，请换一个名称；同一主机下的不同路径段（如 /pr/ 与 /md/）建议各建一份档案"
                    .formatted(name), BizErrorCode.VALIDATION_ERROR);
        }
    }

    private ServiceException refusedStale(SiteProfileView row) {
        return new ServiceException("站点档案「%s」已被他人更新（当前版本 %d），请刷新档案抽屉后基于最新版本重试"
                .formatted(row.name(), row.revision()), BizErrorCode.VERSION_CONFLICT);
    }

    private long requireProject(SiteProfileInput input) {
        if (input.projectId() == null) {
            throw new ServiceException("请先在顶栏「项目」下拉选择项目后，再新建站点档案", BizErrorCode.VALIDATION_ERROR);
        }
        return input.projectId();
    }

    private String requireName(String name) {
        if (name == null || name.isBlank()) {
            throw new ServiceException("档案名称不能为空，请使用团队看得懂的名字（如「商城前台」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        String trimmed = name.trim();
        if (trimmed.length() > 100) {
            throw new ServiceException("档案名称「%s」超过 100 字，请缩短后重试".formatted(trimmed),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    private String requireStartUrl(String startUrl) {
        if (startUrl == null || startUrl.isBlank()) {
            throw new ServiceException("起始地址不能为空，请输入完整网址，示例：https://xx.com/pr/",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return startUrl.trim();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("规则清单无法保存（内容格式不被支持），请检查「路径排除清单」「忽略参数」与「预算覆盖」后重试",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private List<String> readStringList(String stored) {
        try {
            return mapper.readValue(stored, new TypeReference<List<String>>() { });
        } catch (Exception e) {
            return List.of();
        }
    }

    private Map<String, Object> readMap(String stored) {
        try {
            return mapper.readValue(stored, new TypeReference<Map<String, Object>>() { });
        } catch (Exception e) {
            return Map.of();
        }
    }

    private SiteProfileView mapRow(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        java.time.LocalDateTime updatedAt = rs.getTimestamp("updated_at") == null
                ? null : rs.getTimestamp("updated_at").toLocalDateTime();
        return new SiteProfileView(
                rs.getLong("id"),
                rs.getLong("project_id"),
                rs.getString("name"),
                rs.getString("start_url"),
                rs.getString("scope_prefix"),
                readStringList(rs.getString("exclude_globs")),
                readStringList(rs.getString("ignore_params")),
                rs.getBoolean("verbs_guard_enabled"),
                readMap(rs.getString("budget_overrides")),
                rs.getString("login_state_name"),
                rs.getBoolean("enabled"),
                rs.getInt("revision"),
                updatedAt);
    }
}
