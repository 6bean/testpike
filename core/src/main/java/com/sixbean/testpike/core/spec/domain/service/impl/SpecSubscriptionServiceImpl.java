package com.sixbean.testpike.core.spec.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecSubscriptionSaveRequest;
import com.sixbean.testpike.core.spec.api.dto.SubscriptionScanResult;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecPreviewService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import com.sixbean.testpike.core.spec.domain.service.SpecSubscriptionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;

/**
 * <p>create/update validation is the single source for both pull kinds (V357
 * source_kind: url | program_spec); the set-membership fence and provider-holds-spec
 * checks live in {@link #requireProgramReference} and are never mirrored elsewhere.
 */
@Service
public class SpecSubscriptionServiceImpl implements SpecSubscriptionService {

    private static final Logger log = LoggerFactory.getLogger(SpecSubscriptionServiceImpl.class);

 
    static final String EVENT_SPEC_CHANGE_DETECTED = "SPEC_CHANGE_DETECTED";

    /** Pull source kinds (tp_spec_subscription.source_kind, V357). */
    static final String SOURCE_URL = "url";
    static final String SOURCE_PROGRAM_SPEC = "program_spec";

    private static final int DEFAULT_INTERVAL_MINUTES = 1440;
    private static final int MIN_INTERVAL_MINUTES = 1;
    private static final int MAX_INTERVAL_MINUTES = 43200;

    private final NamedParameterJdbcTemplate jdbc;
    private final ApiSpecService apiSpecService;
    private final ApiSpecPreviewService previewService;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AfterCommitExecutor afterCommitExecutor;

    public SpecSubscriptionServiceImpl(NamedParameterJdbcTemplate jdbc,
                                       ApiSpecService apiSpecService,
                                       ApiSpecPreviewService previewService,
                                       NotificationService notificationService,
                                       ProjectOwners projectOwners,
                                       AfterCommitExecutor afterCommitExecutor) {
        this.jdbc = jdbc;
        this.apiSpecService = apiSpecService;
        this.previewService = previewService;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.afterCommitExecutor = afterCommitExecutor;
    }

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, name, url, check_interval_minutes, enabled,
                   last_spec_id, last_status, last_error, last_check_at, created_by, created_at,
                   source_kind, provider_project_id, provider_spec_name
            FROM tp_spec_subscription
            """;

    private static final RowMapper<SpecSubscriptionInfo> MAPPER = (rs, rowNum) -> {
        Timestamp checkAt = rs.getTimestamp("last_check_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new SpecSubscriptionInfo(
                rs.getLong("id"), rs.getLong("project_id"), rs.getString("name"),
                rs.getString("url"), rs.getInt("check_interval_minutes"),
                rs.getBoolean("enabled"),
                rs.getObject("last_spec_id") == null ? null : rs.getLong("last_spec_id"),
                rs.getString("last_status"), rs.getString("last_error"),
                checkAt == null ? null : ViewFormats.formatTs(checkAt),
                rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                createdAt == null ? null : ViewFormats.formatTs(createdAt),
                rs.getString("source_kind"),
                rs.getObject("provider_project_id") == null ? null : rs.getLong("provider_project_id"),
                rs.getString("provider_spec_name"));
    };

    @Override
    public SpecSubscriptionInfo create(long operatorId, SpecSubscriptionSaveRequest request) {
        if (request.projectId() == null) {
            throw new ServiceException("订阅归属项目不能为空，请先在顶栏选定项目，再到「API 测试」页打开「契约订阅」新建",
                    BizErrorCode.VALIDATION_ERROR);
        }
        long projectId = request.projectId();
        String trimmedName = requireText(request.name(), "订阅名称不能为空");
        String kind = normalizeSourceKind(request.sourceKind(), SOURCE_URL);
        int interval = normalizeInterval(request.checkIntervalMinutes());
        String trimmedUrl = null;
        Long providerProjectId = null;
        String providerSpecName = null;
        if (SOURCE_PROGRAM_SPEC.equals(kind)) {
            providerProjectId = request.providerProjectId();
            providerSpecName = request.providerSpecName() == null
                    ? null : request.providerSpecName().trim();
            requireProgramReference(projectId, providerProjectId, providerSpecName);
        } else {
            trimmedUrl = requireHttpUrl(request.url());
        }
        assertNameFree(projectId, trimmedName, null);
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update("""
                INSERT INTO tp_spec_subscription (project_id, name, url, check_interval_minutes,
                    created_by, source_kind, provider_project_id, provider_spec_name)
                VALUES (:pid, :name, :url, :interval, :operatorId, :kind, :providerPid, :providerName)
                """, new MapSqlParameterSource()
                .addValue("pid", projectId).addValue("name", trimmedName)
                .addValue("url", trimmedUrl).addValue("interval", interval)
                .addValue("operatorId", operatorId).addValue("kind", kind)
                .addValue("providerPid", providerProjectId).addValue("providerName", providerSpecName),
                keyHolder, new String[]{"id"});
        return get(Objects.requireNonNull(keyHolder.getKey()).longValue());
    }

    @Override
    public SpecSubscriptionInfo update(long operatorId, long id, SpecSubscriptionSaveRequest request) {
        SpecSubscriptionInfo current = get(id);
        String trimmedName = request.name() == null || request.name().isBlank()
                ? current.name() : requireText(request.name(), "订阅名称不能为空");
        String kind = request.sourceKind() == null || request.sourceKind().isBlank()
                ? normalizeSourceKind(null, current.sourceKind())
                : normalizeSourceKind(request.sourceKind(), SOURCE_URL);
        int interval = request.checkIntervalMinutes() == null
                ? current.checkIntervalMinutes() : normalizeInterval(request.checkIntervalMinutes());
        boolean keepEnabled = request.enabled() == null ? current.enabled() : request.enabled();
        String url = null;
        Long providerProjectId = null;
        String providerSpecName = null;
        if (SOURCE_PROGRAM_SPEC.equals(kind)) {
            providerProjectId = request.providerProjectId() != null
                    ? request.providerProjectId() : current.providerProjectId();
            providerSpecName = request.providerSpecName() == null || request.providerSpecName().isBlank()
                    ? current.providerSpecName() : request.providerSpecName().trim();
            requireProgramReference(current.projectId(), providerProjectId, providerSpecName);
        } else {
            String candidate = request.url() == null || request.url().isBlank()
                    ? current.url() : request.url();
            url = requireHttpUrl(candidate);
        }
        boolean sourceChanged = !Objects.equals(url, current.url())
                || !Objects.equals(kind, current.sourceKind())
                || !Objects.equals(providerProjectId, current.providerProjectId())
                || !Objects.equals(providerSpecName, current.providerSpecName());
        assertNameFree(current.projectId(), trimmedName, id);
        jdbc.update("""
                UPDATE tp_spec_subscription
                SET name = :name, url = :url, check_interval_minutes = :interval, enabled = :enabled,
                    source_kind = :kind, provider_project_id = :providerPid,
                    provider_spec_name = :providerName,
                    last_check_at = CASE WHEN :sourceChanged THEN NULL ELSE last_check_at END,
                    last_error = CASE WHEN :sourceChanged THEN NULL ELSE last_error END
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("name", trimmedName).addValue("url", url)
                .addValue("interval", interval).addValue("enabled", keepEnabled)
                .addValue("kind", kind).addValue("providerPid", providerProjectId)
                .addValue("providerName", providerSpecName)
                .addValue("sourceChanged", sourceChanged).addValue("id", id));
        return get(id);
    }

    @Override
    public void delete(long operatorId, long id) {
        int rows = jdbc.update("DELETE FROM tp_spec_subscription WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows == 0) {
            throw new ServiceException("契约订阅不存在: " + id + "，请在「API 测试」页打开「契约订阅」刷新列表确认后再操作",
                    BizErrorCode.NOT_FOUND);
        }
    }

    @Override
    public PageResult<SpecSubscriptionInfo> list(long projectId, int page, int size) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        int safePage = Math.max(page, 1);
        Long total = jdbc.queryForObject(
                "SELECT count(*) FROM tp_spec_subscription WHERE project_id = :pid",
                new MapSqlParameterSource("pid", projectId), Long.class);
        List<SpecSubscriptionInfo> rows = jdbc.query(SELECT_COLUMNS + """
                 WHERE project_id = :pid
                 ORDER BY id DESC
                 LIMIT :limit OFFSET :offset
                """, new MapSqlParameterSource("pid", projectId)
                .addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize),
                MAPPER);
        return new PageResult<>(rows, total == null ? 0 : total);
    }


    private static String requireText(String name, String message) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new ServiceException(message, BizErrorCode.VALIDATION_ERROR);
        }
        return trimmed;
    }

    private static String requireHttpUrl(String url) {
        String trimmedUrl = url == null ? "" : url.trim();
        if (!trimmedUrl.startsWith("http://") && !trimmedUrl.startsWith("https://")) {
            throw new ServiceException("订阅地址只支持 http/https（当前: " + trimmedUrl + "），请在「API 测试」页的「契约订阅」中改成以 http:// 或 https:// 开头的地址后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return trimmedUrl;
    }

    /**
     * Normalize the pull source kind: blank falls back to the given default, anything
     * outside url|program_spec is a 400 with the accepted set named in the message.
     */
    private static String normalizeSourceKind(String raw, String fallback) {
        String kind = raw == null || raw.isBlank() ? fallback : raw.trim();
        if (!SOURCE_URL.equals(kind) && !SOURCE_PROGRAM_SPEC.equals(kind)) {
            throw new ServiceException("订阅来源只支持 url 或 program_spec（当前: " + kind + "），请在「API 测试」页的「契约订阅」中把来源改成 url 或 program_spec 后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return kind;
    }

    /**
     * Set-internal fence (single source for create and update): the consumer and the
     * provider must share at least one ACTIVE program (V340 lens; archived programs
     * stop granting reach) and the provider must already hold a spec version row with
     * that exact name — otherwise every scan round would only record failures.
     */
    private void requireProgramReference(long consumerProjectId, Long providerProjectId,
                                         String providerSpecName) {
        if (providerProjectId == null || providerSpecName == null || providerSpecName.isBlank()) {
            throw new ServiceException("集内契约订阅必须指定提供方项目与提供方契约名，请在「API 测试」页的「契约订阅」中新建订阅时补填这两项",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long shared = jdbc.queryForObject("""
                SELECT count(*) FROM tp_program_project a
                JOIN tp_program_project b ON a.program_id = b.program_id
                JOIN tp_program p ON p.id = a.program_id AND p.state = 1
                WHERE a.project_id = :consumer AND b.project_id = :provider
                """, new MapSqlParameterSource("consumer", consumerProjectId)
                .addValue("provider", providerProjectId), Long.class);
        if (shared == null || shared == 0) {
            throw new ServiceException("订阅方项目与提供方项目不属于同一个项目集，无法建立集内契约订阅；请先在「项目集管理」页把两个项目归入同一个项目集，或改用 URL 来源订阅",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Long held = jdbc.queryForObject("""
                SELECT count(*) FROM tp_api_spec
                WHERE project_id = :provider AND name = :specName
                """, new MapSqlParameterSource("provider", providerProjectId)
                .addValue("specName", providerSpecName.trim()), Long.class);
        if (held == null || held == 0) {
            throw new ServiceException("提供方项目内还没有契约「" + providerSpecName.trim()
                    + "」，请先在顶栏切到提供方项目、于「API 测试」页导入契约，再切回来订阅",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static int normalizeInterval(Integer checkIntervalMinutes) {
        int interval = checkIntervalMinutes == null ? DEFAULT_INTERVAL_MINUTES : checkIntervalMinutes;
        if (interval < MIN_INTERVAL_MINUTES || interval > MAX_INTERVAL_MINUTES) {
            throw new ServiceException("检查间隔须在 " + MIN_INTERVAL_MINUTES + "~"
                    + MAX_INTERVAL_MINUTES + " 分钟之间（当前: " + interval + "），请在「API 测试」页的「契约订阅」中把检查间隔改到该区间后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        return interval;
    }

    
    private void assertNameFree(long projectId, String name, Long excludeId) {
        Integer dup = jdbc.queryForObject("""
                SELECT count(*) FROM tp_spec_subscription
                WHERE project_id = :pid AND name = :name AND (:excludeId::bigint IS NULL OR id <> :excludeId)
                """, new MapSqlParameterSource("pid", projectId).addValue("name", name)
                .addValue("excludeId", excludeId), Integer.class);
        if (dup != null && dup > 0) {
            throw new ServiceException("项目内已有同名契约订阅「" + name
                    + "」，请在「API 测试」页打开「契约订阅」改用其它名称，或直接编辑现有订阅", BizErrorCode.RESOURCE_CONFLICT);
        }
    }

    @Override
    public SpecSubscriptionInfo get(long id) {
        List<SpecSubscriptionInfo> rows = jdbc.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id), MAPPER);
        if (rows.isEmpty()) {
            throw new ServiceException("契约订阅不存在: " + id + "，请在「API 测试」页打开「契约订阅」刷新列表确认后再操作",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public SubscriptionScanResult scanDue() {
        List<SpecSubscriptionInfo> due = jdbc.query(SELECT_COLUMNS + """
                 WHERE enabled AND (last_check_at IS NULL
                    OR last_check_at <= now() - make_interval(mins => check_interval_minutes))
                 ORDER BY id
                """, new MapSqlParameterSource(), MAPPER);
        int updated = 0;
        int unchanged = 0;
        int failed = 0;
        for (SpecSubscriptionInfo sub : due) {
            long operatorId = sub.createdBy() == null ? 1L : sub.createdBy();
            try {
                // Source routing on the shared success path — the diff/preview/notify
                // branch below stays single source for both pull kinds.
                ApiSpecInfo info = SOURCE_PROGRAM_SPEC.equals(sub.sourceKind())
                        ? apiSpecService.fetchFromProviderSpec(operatorId, sub.projectId(),
                                sub.name(), Objects.requireNonNull(sub.providerProjectId(),
                                        "program_spec row must carry the provider pair"),
                                Objects.requireNonNull(sub.providerSpecName(),
                                        "program_spec row must carry the provider pair"))
                        : apiSpecService.fetchFromUrl(operatorId, sub.projectId(),
                                sub.name(), sub.url());
                updated++;
                markResult(sub.id(), "updated", info.id(), null);
                boolean changed = info.diffSummary() != null && !info.diffSummary().isEmpty();
                if (changed) {
                    previewService.generate(operatorId, info.id());
                    notifyChange(info);
                }
            } catch (ServiceException e) {
                if (Objects.equals(e.getCode(), BizErrorCode.RESOURCE_CONFLICT)) {
                    unchanged++;
                    markResult(sub.id(), "unchanged", sub.lastSpecId(), null);
                } else {
                    failed++;
                    markResult(sub.id(), "failed", sub.lastSpecId(), humanError(e));
                    log.warn("契约订阅拉取失败（订阅 id={}, name={}）: {}",
                            sub.id(), sub.name(), e.getMessage());
                }
            } catch (RuntimeException e) {
                failed++;
                markResult(sub.id(), "failed", sub.lastSpecId(),
                        e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
                log.warn("契约订阅扫描异常（订阅 id={}）: {}", sub.id(), e.getMessage());
            }
        }
        return new SubscriptionScanResult(due.size(), updated, unchanged, failed);
    }

    private static String humanError(ServiceException e) {
        String msg = e.getMessage() == null ? "拉取失败" : e.getMessage();
        return msg.length() <= 500 ? msg : msg.substring(0, 500);
    }

    private void markResult(long subscriptionId, String status, Long specId, String error) {
        jdbc.update("""
                UPDATE tp_spec_subscription
                SET last_status = :status, last_spec_id = :specId, last_error = :error,
                    last_check_at = now()
                WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status).addValue("specId", specId)
                .addValue("error", error).addValue("id", subscriptionId));
    }

    
    private void notifyChange(ApiSpecInfo info) {
        afterCommitExecutor.execute(() -> {
            for (Long userId : projectOwners.resolveWithCreator(info.projectId())) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, info.projectId(),
                            EVENT_SPEC_CHANGE_DETECTED,
                            "订阅的接口契约发生变更，已生成待审预览",
                            "契约「" + info.name() + "」更新至 v" + info.specVersion()
                                    + "，端点有新增/变更/移除，请前往「接口文档 → 导入预览」勾选写入。",
                            EVENT_SPEC_CHANGE_DETECTED + ":" + info.id() + ":" + userId));
                } catch (RuntimeException e) {
                    log.warn("SPEC_CHANGE_DETECTED 发送失败（specId={}, userId={}）: {}",
                            info.id(), userId, e.getMessage());
                }
            }
        });
    }
}
