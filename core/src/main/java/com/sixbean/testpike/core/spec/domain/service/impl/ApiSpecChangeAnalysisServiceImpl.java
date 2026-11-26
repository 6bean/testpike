package com.sixbean.testpike.core.spec.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType;
import com.sixbean.testpike.core.linkage.domain.service.LinkageImpactConsumptionService;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeAnalysisInfo;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecChangeItem;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import com.sixbean.testpike.core.spec.domain.component.DerivedMockKeyLookup;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.spec.domain.component.SpecChangeAnalysisStatus;
import com.sixbean.testpike.core.spec.domain.component.SpecDiff;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecChangeAnalysisService;
import com.sixbean.testpike.core.spec.domain.service.impl.ApiSpecServiceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Contract change-analysis queue implementation (W1c F3-twin, core/spec domain).
 * <p>Old-version anchoring = the row's own prev_spec_id (the version chain is the
 * single source — no second "same name, earlier id" lookup). The three-class diff is
 * recomputed through {@link ApiSpecServiceImpl#diffOf} so the queue and the badge
 * verdicts share one diff spec (zero parallel judgment source).
 * <p>Impact closure is an equality join on the folded contract key: imported requests
 * via tp_apitest_request.contract_endpoint_key (indexed first-class column — no
 * per-row recompute), promoted cases via promoted_case_id with soft-deleted excluded,
 * contract-derived stubs via DerivedMockKeyLookup.stubIdsByKey (same SELECT, third
 * consumer). A change whose folded key matches nothing is still listed with empty
 * impact lists — the queue never fabricates a closure.
 * <p>Upsert on UNIQUE(old_spec_id, new_spec_id): re-analysis refreshes the change
 * list and the detected source, and preserves the human disposition (status, handled
 * columns stay untouched) — the subscription poll re-landing the same pair must not
 * wipe a reviewer's trace nor double the row.
 */
@Service
public class ApiSpecChangeAnalysisServiceImpl implements ApiSpecChangeAnalysisService {

    /** Review-reminder event type (registered in NotificationEventDict + docs/07). */
    static final String EVENT_SPEC_CHANGE_ANALYZED = "SPEC_CHANGE_ANALYZED";

    private static final Logger log =
            LoggerFactory.getLogger(ApiSpecChangeAnalysisServiceImpl.class);

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, old_spec_id, new_spec_id, changes::text AS changes_text,
                   detected_source, status, created_by, created_at,
                   handled_by, handled_at, handle_note
            FROM tp_api_spec_change_analysis
            """;

    private final NamedParameterJdbcTemplate named;
    private final ContractMatchKeyRules matchKeyRules;
    private final DerivedMockKeyLookup derivedMockKeyLookup;
    private final ProjectOwners projectOwners;
    private final NotificationService notificationService;
    private final AfterCommitExecutor afterCommitExecutor;
    private final LinkageImpactConsumptionService linkageConsumption;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiSpecChangeAnalysisServiceImpl(NamedParameterJdbcTemplate named,
                                            ContractMatchKeyRules matchKeyRules,
                                            DerivedMockKeyLookup derivedMockKeyLookup,
                                            ProjectOwners projectOwners,
                                            NotificationService notificationService,
                                            AfterCommitExecutor afterCommitExecutor,
                                            LinkageImpactConsumptionService linkageConsumption) {
        this.named = named;
        this.matchKeyRules = matchKeyRules;
        this.derivedMockKeyLookup = derivedMockKeyLookup;
        this.projectOwners = projectOwners;
        this.notificationService = notificationService;
        this.afterCommitExecutor = afterCommitExecutor;
        this.linkageConsumption = linkageConsumption;
    }

    @Override
    @Transactional
    public ApiSpecChangeAnalysisInfo analyze(long operatorId, long newSpecId, String detectedSource) {
        Map<String, Object> current = readSpecRow(newSpecId);
        Object prevId = current.get("prev_spec_id");
        if (prevId == null) {
            throw new ServiceException("契约版本没有可对照的历史版本（首版上传不产变更分析行，"
                    + "请从第二个版本起做变更分析）；入口：「API 测试」上传新版本契约后在「变更分析」页发起", BizErrorCode.VALIDATION_ERROR);
        }
        long oldSpecId = ((Number) prevId).longValue();
        SpecDiff diff = ApiSpecServiceImpl.diffOf(parseEndpoints(readSpecRow(oldSpecId)),
                parseEndpoints(current));
        if (diff.isEmpty()) {
            throw new ServiceException("契约新旧版本的接口键无差异（端点集合完全一致），"
                    + "无需变更分析；请在「API 测试」核对新版契约的端点集合确有增删改后再发起", BizErrorCode.VALIDATION_ERROR);
        }

        long projectId = ((Number) current.get("project_id")).longValue();
        List<ApiSpecChangeItem> items = closeImpact(projectId, diff);
        String source = detectedSource == null || detectedSource.isBlank()
                ? "upload" : detectedSource.trim();

        Long id = named.queryForObject("""
                INSERT INTO tp_api_spec_change_analysis (project_id, old_spec_id, new_spec_id,
                                                         changes, detected_source, status, created_by)
                VALUES (:projectId, :oldId, :newId, :changes::jsonb, :source, :status, :operatorId)
                ON CONFLICT (old_spec_id, new_spec_id) DO UPDATE
                    SET changes = EXCLUDED.changes,
                        detected_source = EXCLUDED.detected_source
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("oldId", oldSpecId)
                .addValue("newId", newSpecId)
                .addValue("changes", writeJson(items))
                .addValue("source", source)
                .addValue("status", SpecChangeAnalysisStatus.PENDING)
                .addValue("operatorId", operatorId), Long.class);
        ApiSpecChangeAnalysisInfo saved = getById(id);
        notifyReviewers(saved);
        return saved;
    }

    @Override
    public ApiSpecChangeAnalysisInfo get(long newSpecId) {
        List<ApiSpecChangeAnalysisInfo> rows = query(SELECT_COLUMNS + " WHERE new_spec_id = :id",
                new MapSqlParameterSource("id", newSpecId));
        if (rows.isEmpty()) {
            throw new ServiceException("该契约版本没有变更分析记录（newSpecId=" + newSpecId + "），请在「变更分析」页按该契约版本查询或先发起分析",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public PageResult<ApiSpecChangeAnalysisInfo> list(long projectId, Integer status,
                                                      int page, int size) {
        int safePage = Math.max(1, page);
        int safeSize = Math.min(200, Math.max(1, size == 0 ? 20 : size));
        MapSqlParameterSource params = new MapSqlParameterSource("projectId", projectId);
        StringBuilder where = new StringBuilder(" WHERE project_id = :projectId");
        if (status != null) {
            where.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long total = named.queryForObject(
                "SELECT count(*) FROM tp_api_spec_change_analysis" + where, params, Long.class);
        List<ApiSpecChangeAnalysisInfo> rows = query(SELECT_COLUMNS + where
                        + " ORDER BY id DESC LIMIT :limit OFFSET :offset",
                params.addValue("limit", safeSize).addValue("offset", (safePage - 1) * safeSize));
        return new PageResult<>(rows, total == null ? 0L : total);
    }

    @Override
    @Transactional
    public ApiSpecChangeAnalysisInfo handle(long operatorId, long id, String note) {
        ApiSpecChangeAnalysisInfo current = getById(id);
        requirePending(current);
        if (note == null || note.isBlank()) {
            throw new ServiceException("处置备注必填——请写明复核结论与后续动作，留痕才可追溯；入口：「变更分析」页处置框的 note 栏",
                    BizErrorCode.VALIDATION_ERROR);
        }
        applyDisposition(operatorId, id, note);
        // One verdict closes both accounts : the armed
        // ledger rows of this contract save settle on the linkage ledger.
        linkageConsumption.settleImpactsForUpstream(
                ObjectChangedEventType.API_SPEC.objectType(), current.newSpecId());
        return getById(id);
    }

    @Override
    @Transactional
    public List<ApiSpecChangeAnalysisInfo> handleBatch(long operatorId, List<Long> ids, String note) {
        if (ids == null || ids.isEmpty()) {
            throw new ServiceException("请先勾选要处置的变更分析行（入口：「变更分析」页列表左侧复选框）", BizErrorCode.VALIDATION_ERROR);
        }
        if (note == null || note.isBlank()) {
            throw new ServiceException("处置备注必填——请写明复核结论与后续动作，留痕才可追溯；入口：「变更分析」页处置框的 note 栏",
                    BizErrorCode.VALIDATION_ERROR);
        }
        // Validate every row before writing any — a partial batch would leave the
        // reviewer believing rows were disposed that actually were not.
        List<ApiSpecChangeAnalysisInfo> targets = new ArrayList<>();
        for (Long id : new LinkedHashSet<>(ids)) {
            ApiSpecChangeAnalysisInfo row = getById(id);
            requirePending(row);
            targets.add(row);
        }
        List<ApiSpecChangeAnalysisInfo> handled = new ArrayList<>();
        for (ApiSpecChangeAnalysisInfo row : targets) {
            applyDisposition(operatorId, row.id(), note);
            linkageConsumption.settleImpactsForUpstream(
                    ObjectChangedEventType.API_SPEC.objectType(), row.newSpecId());
            handled.add(getById(row.id()));
        }
        return handled;
    }

    @Override
    public long countPending(long projectId) {
        // Same status predicate as the queue list (SpecChangeAnalysisStatus.PENDING
        // single source) — the plan-exit hint must count what the page actually shows.
        Long count = named.queryForObject("""
                SELECT count(*) FROM tp_api_spec_change_analysis
                 WHERE project_id = :projectId AND status = :pending
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("pending", SpecChangeAnalysisStatus.PENDING), Long.class);
        return count == null ? 0 : count;
    }

    /** Pending-only guard shared by single and batch disposition (one rule, no fork). */
    private void requirePending(ApiSpecChangeAnalysisInfo row) {
        if (row.status() != SpecChangeAnalysisStatus.PENDING) {
            throw new ServiceException("该变更分析已处置完毕，无需重复处置（处置人="
                    + row.handledBy() + "）；如需复核历史记录，请在「变更分析」页按已处置筛选查看", BizErrorCode.VALIDATION_ERROR);
        }
    }

    /** status 0→1 with guarded WHERE status = pending; trace columns only. */
    private void applyDisposition(long operatorId, long id, String note) {
        named.update("""
                        UPDATE tp_api_spec_change_analysis
                           SET status = :handled, handled_by = :operatorId,
                               handled_at = now(), handle_note = :note
                         WHERE id = :id AND status = :pending
                        """,
                new MapSqlParameterSource()
                        .addValue("handled", SpecChangeAnalysisStatus.HANDLED)
                        .addValue("pending", SpecChangeAnalysisStatus.PENDING)
                        .addValue("operatorId", operatorId)
                        .addValue("note", note.trim())
                        .addValue("id", id));
    }

    /**
     * Endpoint-level change rows with closed impact lists. Folded keys are batched into
     * one request query and merged with the derived-stub map, so an endpoint never
     * receives a hand-written second join.
     */
    private List<ApiSpecChangeItem> closeImpact(long projectId, SpecDiff diff) {
        // endpointKey -> change type, and endpointKey -> folded match key
        Map<String, String> types = new LinkedHashMap<>();
        Map<String, String> foldedByKey = new LinkedHashMap<>();
        collectKeys(types, foldedByKey, diff.added(), "add");
        collectKeys(types, foldedByKey, diff.changed(), "changed");
        collectKeys(types, foldedByKey, diff.removed(), "removed");

        Map<String, List<Long>> requestIds = new LinkedHashMap<>();
        Map<String, List<Long>> caseIds = new LinkedHashMap<>();
        List<String> foldedKeys = new ArrayList<>(new LinkedHashSet<>(foldedByKey.values()));
        if (!foldedKeys.isEmpty()) {
            named.query("""
                    SELECT rq.contract_endpoint_key AS folded_key, rq.id AS request_id,
                           rq.promoted_case_id AS case_id
                      FROM tp_apitest_request rq
                      JOIN tp_apitest_collection cq ON cq.id = rq.collection_id
                      LEFT JOIN tp_test_case tc ON tc.id = rq.promoted_case_id AND tc.deleted = 0
                     WHERE cq.project_id = :projectId
                       AND rq.contract_endpoint_key IN (:keys)
                     ORDER BY rq.id
                    """, new MapSqlParameterSource("projectId", projectId)
                    .addValue("keys", foldedKeys),
                    (RowCallbackHandler) rs -> {
                        String key = rs.getString("folded_key");
                        requestIds.computeIfAbsent(key, k -> new ArrayList<>())
                                .add(rs.getLong("request_id"));
                        long promoted = rs.getLong("case_id");
                        if (!rs.wasNull()) {
                            caseIds.computeIfAbsent(key, k -> new ArrayList<>()).add(promoted);
                        }
                    });
        }
        Map<String, List<Long>> stubIds = derivedMockKeyLookup.stubIdsByKey(projectId);

        List<ApiSpecChangeItem> items = new ArrayList<>();
        types.forEach((endpointKey, changeType) -> {
            String folded = foldedByKey.get(endpointKey);
            items.add(new ApiSpecChangeItem(endpointKey, changeType,
                    requestIds.getOrDefault(folded, List.of()),
                    caseIds.getOrDefault(folded, List.of()),
                    stubIds.getOrDefault(folded, List.of())));
        });
        return items;
    }

    private void collectKeys(Map<String, String> types, Map<String, String> foldedByKey,
                             List<String> keys, String changeType) {
        for (String key : keys) {
            types.put(key, changeType);
            foldedByKey.put(key, matchKeyRules.matchKeyFromLoose(key));
        }
    }

    private void notifyReviewers(ApiSpecChangeAnalysisInfo info) {
        afterCommitExecutor.execute(() -> {
            for (Long userId : projectOwners.resolveWithCreator(info.projectId())) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, info.projectId(),
                            EVENT_SPEC_CHANGE_ANALYZED,
                            "接口契约改版产生变更，请复核受影响请求与用例",
                            "契约新版本与上一版存在 " + info.changes().size()
                                    + " 处端点差异，已导入请求、升格用例与契约派生 Mock 桩可能需要复核。"
                                    + "请前往「API 测试 → 变更分析队列」查看清单并处置。",
                            EVENT_SPEC_CHANGE_ANALYZED + ":" + info.id() + ":" + userId));
                } catch (RuntimeException e) {
                    log.warn("SPEC_CHANGE_ANALYZED notification failed (analysisId={}, userId={}): {}",
                            info.id(), userId, e.getMessage());
                }
            }
        });
    }

    private Map<String, Object> readSpecRow(long specId) {
        List<Map<String, Object>> rows = named.query("""
                SELECT id, project_id, name, spec_format, spec_title, spec_version,
                       endpoints::text AS endpoints_text, prev_spec_id
                  FROM tp_api_spec WHERE id = :id
                """, new MapSqlParameterSource("id", specId), (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", rs.getLong("id"));
            row.put("project_id", rs.getLong("project_id"));
            row.put("name", rs.getString("name"));
            row.put("spec_format", rs.getString("spec_format"));
            row.put("spec_title", rs.getString("spec_title"));
            row.put("spec_version", rs.getInt("spec_version"));
            row.put("endpoints_text", rs.getString("endpoints_text"));
            row.put("prev_spec_id", rs.getObject("prev_spec_id"));
            return row;
        });
        if (rows.isEmpty()) {
            throw new ServiceException("契约版本不存在（id=" + specId + "），请在「API 测试」契约版本列表确认该版本未被清理", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /** Replay the stored endpoint snapshot (diff input; equals re-parsing the text). */
    private ParsedApiSpec parseEndpoints(Map<String, Object> row) {
        String text = (String) row.get("endpoints_text");
        List<ApiEndpoint> endpoints = readEndpoints(text == null ? "[]" : text);
        return new ParsedApiSpec((String) row.get("spec_format"), (String) row.get("spec_title"),
                null, endpoints);
    }

    private List<ApiSpecChangeAnalysisInfo> query(String sql, MapSqlParameterSource params) {
        List<ApiSpecChangeAnalysisInfo> out = new ArrayList<>();
        named.query(sql, params, (RowCallbackHandler) rs -> out.add(mapRow(rs)));
        return out;
    }

    @Override
    public ApiSpecChangeAnalysisInfo getById(long id) {
        List<ApiSpecChangeAnalysisInfo> rows = query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("变更分析记录不存在（id=" + id + "），请在「变更分析」页刷新列表后重新选择要处置的行", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private ApiSpecChangeAnalysisInfo mapRow(ResultSet rs) throws SQLException {
        return new ApiSpecChangeAnalysisInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("old_spec_id"), rs.getLong("new_spec_id"),
                readItems(rs.getString("changes_text")),
                rs.getInt("status"), SpecChangeAnalysisStatus.label(rs.getInt("status")),
                rs.getString("detected_source"),
                readLong(rs, "created_by"), ViewFormats.formatTs(rs.getTimestamp("created_at")),
                readLong(rs, "handled_by"), ViewFormats.formatTs(rs.getTimestamp("handled_at")),
                rs.getString("handle_note"));
    }

    private String writeJson(List<ApiSpecChangeItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (Exception e) {
            throw new ServiceException("契约变更明细保存失败（本次比对结果无法序列化）——"
                    + "请稍后在「变更分析」页确认本版本比对是否已生成；未生成请在「接口清单」重新导入该契约版本；"
                    + "反复失败请联系管理员查看服务端日志",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private List<ApiSpecChangeItem> readItems(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ApiSpecChangeItem>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("契约变更明细 JSON 反序列化失败（数据损坏？）", e);
        }
    }

    private List<ApiEndpoint> readEndpoints(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ApiEndpoint>>() {
            });
        } catch (Exception e) {
            throw new IllegalStateException("契约清单 JSON 反序列化失败", e);
        }
    }

    private static Long readLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }
}
