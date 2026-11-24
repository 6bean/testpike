package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.explore.api.dto.ExploreCreateInput;
import com.sixbean.testpike.core.explore.domain.service.ExploreService;
import com.sixbean.testpike.core.executor.domain.component.ProdBlacklistGuard;
import com.sixbean.testpike.core.webelement.api.dto.SiteProfileView;
import com.sixbean.testpike.core.webelement.domain.service.SiteProfileService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-site survey dispatch door : turns one saved site
 * profile into an explore session through the SAME
 * {@link ExploreService#create} chain the single-page survey uses — no
 * second INSERT path (shared-logic single source) — plus exactly one
 * running row in {@code tp_site_survey_run}.
 * <p>The goal text rides {@link WebElementSurveyCollector#SITE_SURVEY_GOAL_PREFIX}
 * followed by the profile id and the start URL, so the step relay can route
 * process frames onto the dedicated site kind while candidate pools keep
 * flowing through the single collector ingest gate.
 * <p>{@code rules_snapshot} freezes the merged site rules at dispatch time:
 * profile rules (exclude globs, ignored params, verb guard, login state)
 * over install-time hot keys (budgets, factory verb table, production guard
 * state and deny list), with per-profile budget overrides winning over the
 * key defaults. A profile edited later never rewrites a run in flight —
 * consumers read this column, not the live row.
 * <p>Production hosts (URL host hitting {@code testpike.prod-blacklist})
 * stay refused while {@code element.survey-site.allow-prod} is off; the
 * refusal names the matched host, the settings path and the key literal
 * (error-guidance posture: cause + where to change + what to change).
 */
@Component
public class SiteSurveyDispatch {

    /** Text shape of a ledger start time inside an on-screen refusal. */
    private static final DateTimeFormatter STARTED_AT_TEXT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** Hot keys of the whole-site family, seeded by the install baseline. */
    static final String KEY_ALLOW_PROD = "element.survey-site.allow-prod";
    static final String KEY_DEFAULT_VERBS = "element.survey-site.default-exclude-verbs";
    static final String KEY_MAX_PAGES = "element.survey-site.max-pages";
    static final String KEY_MAX_DEPTH = "element.survey-site.max-depth";
    static final String KEY_INTERVAL_MS = "element.survey-site.interval-ms";
    static final String KEY_DEADLINE_SECONDS = "element.survey-site.deadline-seconds";
    static final String KEY_MAX_LINKS_PER_PAGE = "element.survey-site.max-links-per-page";
    static final String KEY_PER_TEMPLATE_CAP = "element.survey-site.per-template-cap";

    private final SiteProfileService siteProfileService;
    private final ExploreService exploreService;
    private final ProdBlacklistGuard prodBlacklistGuard;
    private final SystemConfigReader configReader;
    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public SiteSurveyDispatch(SiteProfileService siteProfileService,
                              ExploreService exploreService,
                              ProdBlacklistGuard prodBlacklistGuard,
                              SystemConfigReader configReader,
                              NamedParameterJdbcTemplate jdbc,
                              ObjectMapper objectMapper) {
        this.siteProfileService = siteProfileService;
        this.exploreService = exploreService;
        this.prodBlacklistGuard = prodBlacklistGuard;
        this.configReader = configReader;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    /**
     * Dispatch one whole-site survey from a saved profile.
     *
     * @param operatorId acting user id (session audit trail)
     * @param projectId  project owning the profile and the ledger writes
     * @param profileId  site profile to crawl; NOT_FOUND via the profile door
     * @return the new run-ledger row id
     */
    public long dispatch(long operatorId, long projectId, long profileId) {
        SiteProfileView profile = siteProfileService.get(profileId);
        refuseWhileAnotherRunIsInFlight(profile);
        refuseProductionHost(profile.startUrl());
        String goal = WebElementSurveyCollector.SITE_SURVEY_GOAL_PREFIX + profileId
                + " " + profile.startUrl();
        long sessionId = exploreService.create(operatorId, projectId, new ExploreCreateInput(
                goal, profile.startUrl(), null, null, profile.loginStateName()));
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("profileId", profileId)
                .addValue("projectId", projectId)
                .addValue("sessionId", sessionId)
                .addValue("snapshot", writeJson(profile, frozenRules(profile)));
        Long runId = jdbc.queryForObject("""
                INSERT INTO tp_site_survey_run
                        (profile_id, project_id, explore_session_id, status, rules_snapshot)
                VALUES (:profileId, :projectId, :sessionId, 'running', CAST(:snapshot AS JSONB))
                RETURNING id""", params, Long.class);
        return runId == null ? -1L : runId;
    }

    /**
     * Duplicate guard: one profile crawls only once at a time. A second dispatch
     * is refused before any session or ledger row is written, and the refusal
     * names the run that is still going (id + start time) plus the two entries
     * that show its progress, so the operator always has a next step to take.
     */
    private void refuseWhileAnotherRunIsInFlight(SiteProfileView profile) {
        List<Map<String, Object>> inFlight = jdbc.queryForList("""
                SELECT id, started_at FROM tp_site_survey_run
                 WHERE profile_id = :profileId AND status = 'running'
                 ORDER BY started_at DESC
                 LIMIT 1""", new MapSqlParameterSource("profileId", profile.getId()));
        if (inFlight.isEmpty()) {
            return;
        }
        Map<String, Object> running = inFlight.get(0);
        long runningId = ((Number) running.get("id")).longValue();
        throw new ServiceException(String.format(
                "整站测绘未发起：站点档案「%s」已有一条测绘正在进行中（测绘记录 %d，%s 开始，尚未结束）。"
                        + "该档案右侧的按钮此时显示「查看进度」，点它即可查看实时过程；"
                        + "也可在「站点档案」抽屉下方的「测绘记录」列表中找到记录 %d 点「进程详情」回看。"
                        + "等这条测绘结束后即可再次发起。",
                profile.name(), runningId, formatStartedAt(running.get("started_at")), runningId),
                BizErrorCode.VALIDATION_ERROR);
    }

    /** Ledger timestamps read as yyyy-MM-dd HH:mm:ss; unexpected types print as-is. */
    private String formatStartedAt(Object startedAt) {
        if (startedAt instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime().format(STARTED_AT_TEXT);
        }
        if (startedAt instanceof LocalDateTime dateTime) {
            return dateTime.format(STARTED_AT_TEXT);
        }
        return String.valueOf(startedAt);
    }

    /** Production door: matched host + settings path + key literal (R63/R32). */
    private void refuseProductionHost(String startUrl) {
        if (!prodBlacklistGuard.matches(startUrl)
                || configReader.getBoolean(KEY_ALLOW_PROD, false)) {
            return;
        }
        String host = ProdBlacklistGuard.hostOf(startUrl);
        throw new ServiceException(String.format(
                "整站测绘未发起：站点地址 %s 的域名 %s 在生产域名名单中，默认不允许对生产环境测绘。"
                        + "确认该环境可以测绘后，请到「系统设置 → 系统参数」搜索并开启 %s，再重新发起；"
                        + "或改用非生产域名的站点档案。",
                startUrl, host, KEY_ALLOW_PROD), BizErrorCode.VALIDATION_ERROR);
    }

    /**
     * Freeze the merged rules: profile rules win inside their own fields,
     * budget keys take the profile override when present and the install key
     * default otherwise; the guard state (allow switch + deny list) rides the
     * live values read at dispatch time.
     */
    private Map<String, Object> frozenRules(SiteProfileView profile) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("startUrl", profile.startUrl());
        snapshot.put("scopePrefix", profile.scopePrefix());
        snapshot.put("excludeGlobs", profile.excludeGlobs());
        snapshot.put("ignoreParams", profile.ignoreParams());
        snapshot.put("verbsGuardEnabled", profile.verbsGuardEnabled());
        snapshot.put("guardVerbs", defaultVerbTable());
        snapshot.put("maxPages", budgetInt(profile, "maxPages", KEY_MAX_PAGES, 100));
        snapshot.put("maxDepth", budgetInt(profile, "maxDepth", KEY_MAX_DEPTH, 6));
        snapshot.put("intervalMs", budgetInt(profile, "intervalMs", KEY_INTERVAL_MS, 800));
        snapshot.put("deadlineSeconds",
                budgetInt(profile, "deadlineSeconds", KEY_DEADLINE_SECONDS, 1800));
        snapshot.put("maxLinksPerPage",
                budgetInt(profile, "maxLinksPerPage", KEY_MAX_LINKS_PER_PAGE, 60));
        snapshot.put("perTemplateCap",
                budgetInt(profile, "perTemplateCap", KEY_PER_TEMPLATE_CAP, 1));
        snapshot.put("allowProd", configReader.getBoolean(KEY_ALLOW_PROD, false));
        snapshot.put("denyHosts", prodBlacklistGuard.patterns());
        snapshot.put("loginStateName", profile.loginStateName());
        return snapshot;
    }

    /** Profile budget override wins over the install key; unparseable = key. */
    private int budgetInt(SiteProfileView profile, String overrideKey,
                          String configKey, int shippedDefault) {
        Object raw = profile.budgetOverrides() == null
                ? null : profile.budgetOverrides().get(overrideKey);
        if (raw instanceof Number number) {
            return number.intValue();
        }
        if (raw != null) {
            try {
                return Integer.parseInt(String.valueOf(raw).trim());
            } catch (NumberFormatException ignored) {
                // A mistyped override must not sink the dispatch: the door
                // falls back to the hot key, and the ledger row stays honest.
            }
        }
        return configReader.getInt(configKey, shippedDefault);
    }

    /** Factory verb table from the hot key (JSON array; blank/malformed = empty). */
    private List<String> defaultVerbTable() {
        String raw = configReader.getString(KEY_DEFAULT_VERBS, "[]");
        try {
            return objectMapper.readValue(raw, new TypeReference<List<String>>() {
            });
        } catch (Exception malformed) {
            return List.of();
        }
    }

    private String writeJson(SiteProfileView profile, Map<String, Object> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception impossible) {
            throw new ServiceException(String.format(
                    "整站测绘未发起：站点档案「%s」的规则快照生成失败，通常是排除清单或忽略参数清单里含有异常字符。"
                            + "请到「页面元素库」页打开「站点档案」抽屉编辑该档案，把清单字段恢复为普通路径写法（如 /pr/logout*）后重新发起整站测绘。",
                    profile.name()), BizErrorCode.VALIDATION_ERROR);
        }
    }
}
