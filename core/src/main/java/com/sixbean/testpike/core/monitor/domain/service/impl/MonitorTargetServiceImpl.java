package com.sixbean.testpike.core.monitor.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.audit.domain.component.OperatorContext;
import com.sixbean.testpike.core.monitor.api.dto.MonitorCheckLogView;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInput;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetInfo;
import com.sixbean.testpike.core.monitor.api.dto.MonitorTargetPageView;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.monitor.domain.component.LoginProbe;
import com.sixbean.testpike.core.monitor.domain.component.MonitorConfigReader;
import com.sixbean.testpike.core.monitor.domain.component.MonitorProbe;
import com.sixbean.testpike.core.monitor.domain.component.MonitorProbeResult;
import com.sixbean.testpike.core.monitor.domain.service.MonitorEpisodeService;
import com.sixbean.testpike.core.monitor.domain.service.MonitorTargetService;
import com.sixbean.testpike.core.monitor.infrastructure.dataobject.MonitorTarget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class MonitorTargetServiceImpl implements MonitorTargetService {

    private static final Logger log = LoggerFactory.getLogger(MonitorTargetServiceImpl.class);

    static final Set<String> TARGET_TYPES = Set.of("HTTP", "LOGIN", "ONLINE", "DB", "CERT");

    private static final String SELECT_COLUMNS = """
            id, project_id, env_id, name, target_type, probe_config::text AS probe_config,
            check_interval_seconds, enabled, silence_until, silence_reason, last_state,
            last_check_at, last_cause, next_check_at, backoff_level, stable_cycles,
            consecutive_failures, consecutive_successes, env_version, created_at, updated_at
            """;

    private final NamedParameterJdbcTemplate jdbc;
    private final MonitorConfigReader configReader;
    private final ObjectMapper objectMapper;
    
    private final Map<String, MonitorProbe> probesByType;
    
    private final MonitorEpisodeService episodeService;

    public MonitorTargetServiceImpl(NamedParameterJdbcTemplate jdbc, MonitorConfigReader configReader,
                                    ObjectMapper objectMapper, List<MonitorProbe> probes,
                                    MonitorEpisodeService episodeService) {
        this.jdbc = jdbc;
        this.configReader = configReader;
        this.objectMapper = objectMapper;
        this.probesByType = probes.stream()
                .collect(Collectors.toUnmodifiableMap(MonitorProbe::supportedType, p -> p));
        this.episodeService = episodeService;
    }

    @Override
    @Audit(objectType = "monitor_target", action = "create", objectId = "#result ?: #input.name()")
    public long create(long projectId, MonitorTargetInput input) {
        validate(projectId, input);
        MapSqlParameterSource params = writeParams(projectId, input);
        try {
            return jdbc.queryForObject("INSERT INTO tp_monitor_target "
                            + "(project_id, env_id, name, target_type, probe_config, check_interval_seconds, "
                            + "enabled, env_version, next_check_at, created_by) "
                            + "VALUES (:pid, :eid, :name, :type, CAST(:cfg AS jsonb), :interval, "
                            + ":enabled, :envVersion, now(), :by) "
                            + "RETURNING id",
                    params, Long.class);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("项目内已存在同名监控目标「" + input.name() + "」；请前往「监控中心」点击「新建监控目标」改用其他名称后重新提交",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    @Audit(objectType = "monitor_target", action = "update", objectId = "#id")
    public void update(long id, MonitorTargetInput input) {
        MonitorTarget target = findOr404(id);
        validate(target.getProjectId(), input);
        jdbc.update("UPDATE tp_monitor_target SET env_id = :eid, name = :name, target_type = :type, "
                + "probe_config = CAST(:cfg AS jsonb), check_interval_seconds = :interval, "
                + "enabled = :enabled, backoff_level = 0, stable_cycles = 0, "
                + "consecutive_failures = 0, consecutive_successes = 0, next_check_at = now(), "
                + "updated_at = now() WHERE id = :id",
                writeParams(target.getProjectId(), input).addValue("id", id));
    }

    @Override
    public MonitorTargetInfo get(long id) {
        return toInfo(findOr404(id));
    }

    @Override
    public List<MonitorTargetInfo> list(long projectId) {
        // Zero-drift delegation: same query path as the paged channel, whole window.
        return queryPage(projectId, 1, 0, null, null, null, null, null).list();
    }

    @Override
    public MonitorTargetPageView page(long projectId, int page, int pageSize, String sortBy, String sortDir) {
        return page(projectId, page, pageSize, sortBy, sortDir, null, null, null);
    }

    @Override
    public MonitorTargetPageView page(long projectId, int page, int pageSize, String sortBy, String sortDir,
                                      String status, String targetType, String keyword) {
        if (page < 1) {
            throw new ServiceException("监控目标列表页码必须从 1 开始，收到：" + page + "；请在「监控中心」列表底部的分页控件点击第 1 页或更后的页码", BizErrorCode.VALIDATION_ERROR);
        }
        if (pageSize < 1) {
            throw new ServiceException("监控目标列表每页条数必须大于 0，收到：" + pageSize + "；请在「监控中心」列表底部的分页控件重新选择每页条数", BizErrorCode.VALIDATION_ERROR);
        }
        return queryPage(projectId, page, Math.min(pageSize, MAX_PAGE_SIZE), sortBy, sortDir,
                status, targetType, keyword);
    }

    /**
     * Sort whitelist for the wall (key = api-facing name, value = physical column).
     * Validation is delegated to the shared {@link SortColumnPolicy} single source — no
     * second rejection logic and no user input inside SQL (only whitelisted column names
     * are interpolated; window bounds travel as bind parameters).
     */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name", "targetType", "target_type", "envId", "env_id",
            "enabled", "enabled", "lastState", "last_state",
            "lastCheckAt", "last_check_at", "nextCheckAt", "next_check_at");

    /**
     * Wall status filter domain — one-to-one with the tile precedence
     * (disabled &gt; silenced &gt; probe state &gt; unknown), so a filtered window always
     * shows what the tiles would color. Case-insensitive at the api surface.
     */
    private static final Set<String> WALL_STATUSES = Set.of(
            "DISABLED", "SILENCED", "UP", "DOWN", "FLAPPING", "RECOVERING", "UNKNOWN");

    private MonitorTargetPageView queryPage(long projectId, int page, int pageSizeOrUnlimited,
                                            String sortBy, String sortDir,
                                            String status, String targetType, String keyword) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "监控目标");
        String dir = "desc".equalsIgnoreCase(sortDir == null ? "" : sortDir.trim()) ? "DESC" : "ASC";
        String order = " ORDER BY " + (column == null ? "id" : column) + " " + dir + ", id ASC";
        StringBuilder where = new StringBuilder(" WHERE project_id = :pid");
        MapSqlParameterSource filters = new MapSqlParameterSource("pid", projectId);
        applyWallFilters(where, filters, status, targetType, keyword);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tp_monitor_target" + where, filters, Long.class);
        boolean windowed = pageSizeOrUnlimited > 0;
        MapSqlParameterSource ps = filters
                .addValue("lim", pageSizeOrUnlimited)
                .addValue("off", (long) (page - 1) * pageSizeOrUnlimited);
        List<MonitorTarget> rows = jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_monitor_target"
                        + where + order
                        + (windowed ? " LIMIT :lim OFFSET :off" : ""),
                ps, new BeanPropertyRowMapper<>(MonitorTarget.class));
        int echoSize = windowed ? pageSizeOrUnlimited : rows.size();
        return new MonitorTargetPageView(rows.stream().map(this::toInfo).toList(),
                total == null ? 0L : total, page, echoSize);
    }

    /**
     * Server-side wall filters (status/targetType/keyword) — the same WHERE feeds both
     * the count and the window, so {@code total} is always the whole filtered count.
     * An expired silence is intentionally not re-checked here: tiles keep the row as
     * silenced until the scheduler clears the window (what-you-see-is-what-you-filter).
     * User-typed % and _ in the keyword stay literal via ESCAPE. Blank/null = absent.
     */
    private void applyWallFilters(StringBuilder where, MapSqlParameterSource params,
                                  String status, String targetType, String keyword) {
        String statusToken = trimToNull(status);
        if (statusToken != null) {
            String normalized = statusToken.toUpperCase();
            if (!WALL_STATUSES.contains(normalized)) {
                throw new ServiceException("监控状态筛选仅支持 DISABLED/SILENCED/UP/DOWN/FLAPPING/"
                        + "RECOVERING/UNKNOWN，收到「" + statusToken + "」；请在「监控中心」筛选行的「状态筛选」下拉中选择上述之一", BizErrorCode.VALIDATION_ERROR);
            }
            switch (normalized) {
                case "DISABLED" -> where.append(" AND enabled = FALSE");
                case "SILENCED" -> where.append(" AND enabled = TRUE AND silence_until IS NOT NULL");
                case "UNKNOWN" -> where.append(" AND enabled = TRUE AND silence_until IS NULL"
                        + " AND last_state = 'UNKNOWN'");
                default -> {
                    where.append(" AND enabled = TRUE AND silence_until IS NULL AND last_state = :statusState");
                    params.addValue("statusState", normalized);
                }
            }
        }
        String typeToken = trimToNull(targetType);
        if (typeToken != null) {
            String normalized = typeToken.toUpperCase();
            if (!TARGET_TYPES.contains(normalized)) {
                throw new ServiceException("监控类型仅支持 HTTP/LOGIN/ONLINE/DB/CERT，收到「"
                        + typeToken + "」；请在「监控中心」筛选行的「类型筛选」下拉中选择上述之一", BizErrorCode.VALIDATION_ERROR);
            }
            where.append(" AND target_type = :typeFilter");
            params.addValue("typeFilter", normalized);
        }
        String keywordToken = trimToNull(keyword);
        if (keywordToken != null) {
            where.append(" AND name ILIKE :kw ESCAPE '\\'");
            params.addValue("kw", "%" + escapeLikeLiterals(keywordToken) + "%");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Keep user-typed % / _ literal inside the ILIKE pattern (backslash is the escape char). */
    private static String escapeLikeLiterals(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    @Audit(objectType = "monitor_target", action = "toggle", objectId = "#id")
    public void updateEnabled(long id, boolean enabled) {
        findOr404(id);
        jdbc.update("UPDATE tp_monitor_target SET enabled = :en, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("en", enabled).addValue("id", id));
    }

    @Override
    @Audit(objectType = "monitor_target", action = "delete", objectId = "#id")
    public void delete(long id) {
        findOr404(id);
        jdbc.update("DELETE FROM tp_monitor_target WHERE id = :id",
                new MapSqlParameterSource("id", id));
    }

    @Override
    @Audit(objectType = "monitor_target", action = "silence", objectId = "#id")
    public void silence(long id, int minutes, String reason) {
        findOr404(id);
        if (reason == null || reason.isBlank()) {
            throw new ServiceException("静默必须填写理由（将记入审计）；请在「监控中心」目标卡点击「静默1小时」并在弹窗填写理由（如「版本发布窗口」）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (minutes <= 0 || minutes > 7 * 24 * 60) {
            throw new ServiceException("静默时长需在 1 分钟到 7 天之间；请前往「监控中心」点击「静默1小时」重新填写 1~10080 之间的分钟数", BizErrorCode.VALIDATION_ERROR);
        }
        jdbc.update("UPDATE tp_monitor_target SET silence_until = now() + make_interval(mins => :m), "
                + "silence_reason = :reason, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("m", minutes).addValue("reason", reason.trim())
                        .addValue("id", id));
    }

    @Override
    @Audit(objectType = "monitor_target", action = "unsilence", objectId = "#id")
    public void clearSilence(long id) {
        findOr404(id);
        jdbc.update("UPDATE tp_monitor_target SET silence_until = NULL, silence_reason = NULL, "
                + "updated_at = now() WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public int scanDueTargets() {
        List<MonitorTarget> due = findRows("enabled = TRUE "
                + "AND next_check_at IS NOT NULL AND next_check_at <= now() "
                + "AND (silence_until IS NULL OR silence_until < now())",
                new MapSqlParameterSource());
        for (MonitorTarget target : due) {
            MonitorProbe probe = probesByType.get(target.getTargetType());
            if (probe == null) {
                jdbc.update("INSERT INTO tp_monitor_check_log (target_id, outcome, detail) "
                                + "VALUES (:tid, 'deferred', '探针未接线（P2 接线前占位）')",
                        new MapSqlParameterSource("tid", target.getId()));
                advanceStableCycle(target);
                continue;
            }
            MonitorProbeResult result;
            try {
                result = probe.probe(target);
            } catch (Exception e) {
                log.warn("监控目标 {} 探针异常: {}", target.getId(), e.getMessage());
                result = MonitorProbeResult.fail(null, "探针异常: " + e.getMessage());
            }
            if (result.success()) {
                jdbc.update("INSERT INTO tp_monitor_check_log (target_id, outcome, latency_ms) "
                        + "VALUES (:tid, 'ok', :lat)",
                        new MapSqlParameterSource("tid", target.getId()).addValue("lat", result.latencyMs()));
                advanceStableCycle(target);
                evaluateQuietly(target.getId());
            } else {
                recordProbeFailure(target.getId(), result.cause());
            }
        }
        return due.size();
    }

    @Override
    public List<MonitorCheckLogView> recentCheckLogs(long targetId, int limit) {
        findOr404(targetId);
        int capped = Math.min(limit <= 0 ? MAX_CHECK_LOG_LIMIT : limit, MAX_CHECK_LOG_LIMIT);
        return jdbc.queryForList(
                        "SELECT checked_at, outcome, latency_ms, detail FROM tp_monitor_check_log "
                                + "WHERE target_id = :tid ORDER BY checked_at DESC LIMIT :lim",
                        new MapSqlParameterSource("tid", targetId).addValue("lim", capped))
                .stream().map(MonitorTargetServiceImpl::toCheckLogView).toList();
    }

    private static MonitorCheckLogView toCheckLogView(Map<String, Object> row) {
        Object checkedAt = row.get("checked_at");
        LocalDateTime at = checkedAt instanceof java.time.OffsetDateTime odt
                ? odt.toLocalDateTime()
                : ((java.sql.Timestamp) checkedAt).toLocalDateTime();
        Object latency = row.get("latency_ms");
        return new MonitorCheckLogView(at, (String) row.get("outcome"),
                latency == null ? null : ((Number) latency).intValue(), (String) row.get("detail"));
    }

    
    private void evaluateQuietly(long targetId) {
        try {
            episodeService.evaluate(targetId);
        } catch (Exception e) {
            log.warn("监控目标 {} 状态机评估失败（下轮重评）: {}", targetId, e.getMessage());
        }
    }

    @Override
    public void recordProbeFailure(long id, String cause) {
        jdbc.update("INSERT INTO tp_monitor_check_log (target_id, outcome, detail) "
                        + "VALUES (:tid, 'fail', :cause)",
                new MapSqlParameterSource("tid", id).addValue("cause", trim(cause, 500)));
        jdbc.update("UPDATE tp_monitor_target SET consecutive_failures = consecutive_failures + 1, "
                + "consecutive_successes = 0, stable_cycles = 0, backoff_level = 0, "
                + "last_check_at = now(), last_cause = :cause, next_check_at = now() + "
                + "make_interval(secs => :base), updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("cause", trim(cause, 300)).addValue("id", id)
                        .addValue("base", minIntervalSeconds()));
        evaluateQuietly(id);
    }

    @Override
    public int resetStaleSnapshots() {
        Integer n = jdbc.queryForObject("WITH stale AS (UPDATE tp_monitor_target t "
                + "SET backoff_level = 0, stable_cycles = 0, next_check_at = now(), updated_at = now() "
                + "FROM tp_project_env e WHERE t.env_id = e.id AND t.env_version <> e.version "
                + "RETURNING 1) SELECT count(*) FROM stale", new MapSqlParameterSource(), Integer.class);
        int reset = n == null ? 0 : n;
        if (reset > 0) {
            jdbc.update("UPDATE tp_monitor_target t SET env_version = e.version "
                    + "FROM tp_project_env e WHERE t.env_id = e.id", new MapSqlParameterSource());
        }
        return reset;
    }

    @Override
    public int intervalSecondsForLevel(int level) {
        if (level <= 0) {
            return minIntervalSeconds();
        }
        int[] ladder = ladderSeconds();
        return ladder[Math.min(level, ladder.length) - 1];
    }

    @Override
    public String configValue(String key) {
        return configReader.value(key);
    }


 
    private void advanceStableCycle(MonitorTarget target) {
        int need = configReader.intValue(MonitorConfigReader.KEY_STABLE_CYCLES);
        int cycles = target.getStableCycles() + 1;
        int level = target.getBackoffLevel();
        if (cycles >= need) {
            level = level + 1;
            cycles = 0;
        }
        jdbc.update("UPDATE tp_monitor_target SET stable_cycles = :cycles, backoff_level = :level, "
                + "consecutive_successes = consecutive_successes + 1, consecutive_failures = 0, "
                + "last_check_at = now(), next_check_at = now() + make_interval(secs => :gap), "
                + "updated_at = now() WHERE id = :id",
                new MapSqlParameterSource("cycles", cycles).addValue("level", level)
                        .addValue("gap", intervalSecondsForLevel(level)).addValue("id", target.getId()));
    }

    private void validate(long projectId, MonitorTargetInput input) {
        if (input.targetType() == null || !TARGET_TYPES.contains(input.targetType().trim().toUpperCase())) {
            throw new ServiceException("监控类型仅支持 HTTP/LOGIN/ONLINE/DB/CERT，收到「"
                    + input.targetType() + "」；请在「新建监控目标」表单的「探测类型」中选择上述之一", BizErrorCode.VALIDATION_ERROR);
        }
        int floor = minIntervalSeconds();
        if (input.checkIntervalSeconds() == null || input.checkIntervalSeconds() < floor) {
            throw new ServiceException("探测频率不得低于 " + floor + " 秒（测试平台不能变成 DDoS 源；请在「新建监控目标」把探测频率调高到不低于该秒数后重新提交，"
                    + "可经配置键 " + MonitorConfigReader.KEY_MIN_INTERVAL + " 调整下限（管理员在「系统配置」中修改）)", BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> envProjects = jdbc.queryForList(
                "SELECT project_id FROM tp_project_env WHERE id = :eid",
                new MapSqlParameterSource("eid", input.envId()), Long.class);
        if (envProjects.isEmpty() || !envProjects.get(0).equals(projectId)) {
            throw new ServiceException("环境不存在或不属于本项目；请在「新建监控目标」的「监控环境」下拉中选择本项目下的环境", BizErrorCode.FORBIDDEN);
        }
        if ("LOGIN".equalsIgnoreCase(input.targetType().trim())) {
            validateLoginCredentialRef(input.probeConfig());
        }
    }

    
    private void validateLoginCredentialRef(Map<String, Object> config) {
        if (config == null) {
            throw new ServiceException("LOGIN 监控需配置 probe_config（url + credential 凭据引用）；请在「新建监控目标」填写探测地址并选择「登录凭据引用」",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (config.containsKey("password") || config.containsKey("username")) {
            throw new ServiceException("LOGIN 探测凭证禁用明文；请先在「凭据保险箱」登记凭据，"
                    + "再在「新建监控目标」的「登录凭据引用」中选择 @cred/ 开头的引用", BizErrorCode.VALIDATION_ERROR);
        }
        if (LoginProbe.isStateReadMode(textOf(config, LoginProbe.KEY_MODE))) {
            // Direct-read shape (LoginProbe form two): a ledger row name plus the
            // protected URL. Whether that row can actually answer is judged once,
            // at probe time, by SessionStateSupport.probeAuthHeader — this gate
            // checks only that the shape could ever probe.
            String stateName = textOf(config, LoginProbe.KEY_STATE_NAME);
            String checkUrl = textOf(config, LoginProbe.KEY_CHECK_URL);
            if (stateName.isEmpty() || checkUrl.isEmpty()) {
                throw new ServiceException("登录态直读探测需要 stateName 与 checkUrl 两项："
                        + "stateName 在「登录态账本」页选取条目（token 或 cookie_set 形态可直读，"
                        + "浏览器快照形态请用于 UI 用例执行），checkUrl 填被测系统的受保护或健康地址",
                        BizErrorCode.VALIDATION_ERROR);
            }
            return;
        }
        Object credential = config.get(LoginProbe.KEY_CREDENTIAL);
        if (credential == null || !String.valueOf(credential).startsWith("@cred/")) {
            throw new ServiceException("LOGIN 探测凭证必须为 @cred/ 凭据引用；请在「新建监控目标」的「登录凭据引用」中选择（形如 @cred/键名，键先在「凭据保险箱」登记）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** Trimmed text of one probe_config key; absent or null becomes empty. */
    private static String textOf(Map<String, Object> config, String key) {
        Object raw = config.get(key);
        return raw == null ? "" : String.valueOf(raw).trim();
    }

    private MapSqlParameterSource writeParams(long projectId, MonitorTargetInput input) {
        Integer envVersion = jdbc.queryForObject(
                "SELECT version FROM tp_project_env WHERE id = :eid",
                new MapSqlParameterSource("eid", input.envId()), Integer.class);
        return new MapSqlParameterSource()
                .addValue("pid", projectId)
                .addValue("eid", input.envId())
                .addValue("name", input.name().trim())
                .addValue("type", input.targetType().trim().toUpperCase())
                .addValue("cfg", writeJson(input.probeConfig()))
                .addValue("interval", input.checkIntervalSeconds())
                .addValue("enabled", input.enabled() == null || input.enabled())
                .addValue("envVersion", envVersion == null ? 0 : envVersion)
                .addValue("by", OperatorContext.currentOperatorId());
    }

    private String writeJson(Map<String, Object> config) {
        try {
            return objectMapper.writeValueAsString(config == null ? Map.of() : config);
        } catch (JsonProcessingException e) {
            throw new ServiceException("探针配置序列化失败；请检查探测配置中是否含异常字符后重新提交，仍失败请联系管理员在「系统配置」核查", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private MonitorTarget findOr404(long id) {
        List<MonitorTarget> rows = findRows("id = :id", new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("监控目标不存在（id=" + id + "）；请在「监控中心」刷新列表后重新选择目标（可能已被删除或不在当前项目）", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private List<MonitorTarget> findRows(String where, MapSqlParameterSource params) {
        return jdbc.query("SELECT " + SELECT_COLUMNS + " FROM tp_monitor_target WHERE " + where,
                params, new BeanPropertyRowMapper<>(MonitorTarget.class));
    }

    private MonitorTargetInfo toInfo(MonitorTarget t) {
        return new MonitorTargetInfo(t.getId(), t.getProjectId(), t.getEnvId(), t.getName(),
                t.getTargetType(), t.getProbeConfig(), t.getCheckIntervalSeconds(), t.isEnabled(),
                t.getSilenceUntil(), t.getSilenceReason(), t.getLastState(), t.getLastCheckAt(),
                t.getLastCause(), t.getBackoffLevel(), t.getConsecutiveFailures());
    }

    private int minIntervalSeconds() {
        return configReader.intValue(MonitorConfigReader.KEY_MIN_INTERVAL);
    }

    private int[] ladderSeconds() {
        String raw = configValue(MonitorConfigReader.KEY_BACKOFF_LADDER);
        String[] parts = raw.split(",");
        int[] ladder = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                ladder[n++] = Integer.parseInt(p.trim());
            } catch (NumberFormatException ignored) {
            }
        }
        if (n == 0) {
            return new int[]{300, 900};
        }
        return java.util.Arrays.copyOf(ladder, n);
    }

    private static String trim(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
