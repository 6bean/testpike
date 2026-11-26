package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetDetail;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPageView;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPreviewDep;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetPreviewStep;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunSetStep;
import com.sixbean.testpike.core.apitest.api.dto.RunSetCreateInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiRequestSnapshotSupport;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetAdvancer;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunSetPlanSupport;
import com.sixbean.testpike.core.apitest.domain.component.ApiTestEnvGuard;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunSetService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.ApiRunSetRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Collection-run ledger implementation ; dev DB, NamedParameterJdbcTemplate
 * baseline path, no mocks).
 * <p>create = the launch instant: mode validated against the shared plan grammar,
 * env whitelist guard shared with single-request runs (ApiTestEnvGuard, no second
 * spectrum), definitions frozen in tree order (listByCollection ORDER BY id — the
 * existing "tree order = id order" baseline), pool snapshot taken from the live
 * collection row for display/bookkeeping, child runs inserted as pending rows.
 * <p>Dispatching the first hop and advancing on results is the next segment's
 * orchestrator — this class deliberately performs no executor calls so the ledger
 * stays the single source of what was pinned.
 */
@Service
public class ApiRunSetServiceImpl implements ApiRunSetService {

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApiCollectionService apiCollectionService;
    private final ApiRequestService apiRequestService;
    private final ApiTestEnvGuard envGuard;
    private final ApiRunSetPlanSupport planSupport;
    private final ApiRunSetAdvancer advancer;

    public ApiRunSetServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                @Lazy ApiCollectionService apiCollectionService,
                                @Lazy ApiRequestService apiRequestService,
                                ApiTestEnvGuard envGuard,
                                ApiRunSetPlanSupport planSupport,
                                ApiRunSetAdvancer advancer) {
        this.namedJdbc = namedJdbc;
        this.apiCollectionService = apiCollectionService;
        this.apiRequestService = apiRequestService;
        this.envGuard = envGuard;
        this.planSupport = planSupport;
        this.advancer = advancer;
    }

    @Override
    @Transactional
    @Audit(objectType = "apitestRunSet", action = "create", after = "#result")
    public ApiRunSetInfo create(Long operatorId, RunSetCreateInput input) {
        if (input == null || input.collectionId() == null || input.collectionId() <= 0) {
            throw new ServiceException("集合运行必须指定目标集合——请在「接口测试」页先选择集合再发起运行", BizErrorCode.VALIDATION_ERROR);
        }
        String mode = input.interruptionMode() == null || input.interruptionMode().isBlank()
                ? ApiRunSetPlanSupport.MODE_STOP
                : input.interruptionMode().trim();
        // Mode domain guard is the plan component's single source (no second check here).
        ApiRunSetPlanSupport.requireValidMode(mode);
        ApiCollectionInfo collection = apiCollectionService.get(input.collectionId());
        envGuard.requireSameProject(input.envId(), collection.projectId());

        List<ApiRequestInfo> requests = apiRequestService.list(collection.id());
        if (requests.isEmpty()) {
            throw new ServiceException("集合内没有请求，无法发起集合运行——请先在「接口测试」页该集合下新建请求后重试", BizErrorCode.VALIDATION_ERROR);
        }
        String pinnedJson = pinDefinitions(requests);
        // Fail fast on a broken snapshot before touching the ledger (the plan must
        // parse — same component the advancer will use later).
        planSupport.buildPlan(pinnedJson);

        String initialVars = namedJdbc.queryForObject(
                "SELECT vars_json::text FROM tp_apitest_collection WHERE id = :id",
                new MapSqlParameterSource("id", collection.id()), String.class);

        Long setId = namedJdbc.queryForObject("""
                INSERT INTO tp_apitest_run_set (collection_id, project_id, env_id, name,
                        interruption_mode, status, pinned_json, initial_vars_json, total_steps, created_by)
                VALUES (:collectionId, :projectId, :envId, :name, :mode, 0, :pinned::jsonb,
                        :initialVars::jsonb, :total, :operatorId)
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("collectionId", collection.id())
                .addValue("projectId", collection.projectId())
                .addValue("envId", input.envId())
                .addValue("name", input.name() == null ? "" : input.name())
                .addValue("mode", mode)
                .addValue("pinned", pinnedJson)
                .addValue("initialVars", initialVars == null ? "{}" : initialVars)
                .addValue("total", requests.size())
                .addValue("operatorId", operatorId), Long.class);

        int seq = 0;
        for (ApiRequestInfo req : requests) {
            seq++;
            namedJdbc.update("""
                    INSERT INTO tp_apitest_run (request_id, env_id, status, run_set_id, seq_no, created_by)
                    VALUES (:requestId, :envId, 0, :setId, :seq, :operatorId)
                    """, new MapSqlParameterSource()
                    .addValue("requestId", req.id())
                    .addValue("envId", input.envId())
                    .addValue("setId", setId)
                    .addValue("seq", seq)
                    .addValue("operatorId", operatorId));
        }
        // First hop goes out once this ledger transaction commits
        // the orchestrator dispatches one hop at a time; no env selected = the
        // rows honestly wait at pending, same posture as single-request runs).
        advancer.dispatchAfterCommit(setId);
        // The ledger is opened and the first dispatch is scheduled: the read-back
        // keeps the row honest (status flips 0→1 for the running set after commit).
        return get(setId);
    }

    @Override
    public ApiRunSetInfo get(long runSetId) {
        List<ApiRunSetRow> rows = queryRows(" WHERE s.id = :id", new MapSqlParameterSource("id", runSetId));
        return rows.stream().findFirst()
                .map(ApiRunSetServiceImpl::toInfo)
                .orElseThrow(() -> new ServiceException("集合运行记录不存在或已清理——可在「接口测试」页的「接口集运行记录」中查看其余记录", BizErrorCode.NOT_FOUND));
    }

    @Override
    public ApiRunSetDetail detail(long runSetId) {
        ApiRunSetInfo ledger = get(runSetId);
        List<ApiRunSetStep> steps = namedJdbc.query("""
                SELECT r.id AS runId, r.request_id AS requestId, r.seq_no AS seqNo,
                       q.name, q.method, q.protocol, r.status, r.duration_ms AS durationMs,
                       r.error_msg AS errorMsg,
                       jsonb_array_length(COALESCE(r.assert_detail, '[]'::jsonb)) AS assertCount
                  FROM tp_apitest_run r
                  JOIN tp_apitest_request q ON r.request_id = q.id
                 WHERE r.run_set_id = :setId
                 ORDER BY r.seq_no
                """, new MapSqlParameterSource("setId", runSetId),
                (rs, n) -> new ApiRunSetStep(rs.getLong("runId"), rs.getLong("requestId"),
                        rs.getInt("seqNo"), rs.getString("name"), rs.getString("method"),
                        rs.getInt("status"),
                        rs.getObject("durationMs") == null ? null : rs.getLong("durationMs"),
                        rs.getString("errorMsg"), rs.getString("protocol"),
                        rs.getInt("assertCount")));
        return new ApiRunSetDetail(ledger, steps);
    }

    @Override
    public ApiRunSetPageView listByCollection(long collectionId, int page, int pageSize) {
        // Existence/ownership verdict stays the collection service's single point.
        apiCollectionService.get(collectionId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource("collectionId", collectionId)
                .addValue("limit", window.size()).addValue("offset", window.offset());
        Long total = namedJdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_apitest_run_set WHERE collection_id = :collectionId",
                new MapSqlParameterSource("collectionId", collectionId), Long.class);
        List<ApiRunSetInfo> list = queryRows(" WHERE s.collection_id = :collectionId"
                        + " ORDER BY s.created_at DESC, s.id DESC LIMIT :limit OFFSET :offset", params)
                .stream().map(ApiRunSetServiceImpl::toInfo).toList();
        return new ApiRunSetPageView(list, total == null ? 0L : total, window.page(), window.size());
    }

    @Override
    public List<ApiRunSetPreviewStep> preview(long collectionId) {
        // Ownership/non-existence reuse the collection service's single point (404).
        apiCollectionService.get(collectionId);
        List<ApiRequestInfo> requests = apiRequestService.list(collectionId);
        if (requests.isEmpty()) {
            return List.of();
        }
        // Same snapshot + same plan component the launch and the advancer use:
        // the preview arrows are literally the dispatch dependency graph, so a
        // user reading the card sees what the run will actually do (no second
        // {{VAR:...}} parser anywhere in the stack).
        ApiRunSetPlanSupport.RunSetPlan plan = planSupport.buildPlan(pinDefinitions(requests));
        List<ApiRunSetPreviewStep> out = new java.util.ArrayList<>();
        for (int i = 0; i < plan.steps().size(); i++) {
            ApiRunSetPlanSupport.PinnedStep step = plan.steps().get(i);
            ApiRequestInfo req = requests.get(i);
            List<ApiRunSetPreviewDep> deps = new java.util.ArrayList<>();
            for (String key : step.references()) {
                Producer producer = findProducer(plan.steps(), i, key);
                if (producer != null) {
                    deps.add(new ApiRunSetPreviewDep(key, producer.requestId(), producer.earlier()));
                }
            }
            out.add(new ApiRunSetPreviewStep(req.id(), step.seqNo(), req.name(), req.method(),
                    req.urlPath(), List.copyOf(step.extracts()), List.copyOf(step.references()),
                    List.copyOf(deps)));
        }
        return List.copyOf(out);
    }

    /** Producer lookup: the nearest earlier extractor wins; a later one is flagged. */
    private static Producer findProducer(List<ApiRunSetPlanSupport.PinnedStep> steps,
                                         int consumerIndex, String key) {
        // Runtime truth = last writer before the consumer (the pool overwrites),
        // so scan backwards first; only when nothing earlier produces the key do
        // we look forward and flag the out-of-order reference honestly.
        for (int j = consumerIndex - 1; j >= 0; j--) {
            if (steps.get(j).extracts().contains(key)) {
                return new Producer(steps.get(j).requestId(), true);
            }
        }
        for (int j = consumerIndex + 1; j < steps.size(); j++) {
            if (steps.get(j).extracts().contains(key)) {
                return new Producer(steps.get(j).requestId(), false);
            }
        }
        return null;
    }

    /** Where a referenced key is produced inside this collection (null = external pool/global). */
    private record Producer(long requestId, boolean earlier) {
    }

    @Override
    public List<Long> childRunIds(long runSetId) {
        return namedJdbc.query("""
                SELECT id FROM tp_apitest_run WHERE run_set_id = :setId ORDER BY seq_no
                """, new MapSqlParameterSource("setId", runSetId), (rs, n) -> rs.getLong(1));
    }

    /**
 * Freeze the definition rows in tree order — delegates to the shared
 * snapshot freezer the flow template pins the identical
 * shape through the identical code path, red line 6 no second copy).
 */
    private String pinDefinitions(List<ApiRequestInfo> requests) {
        return ApiRequestSnapshotSupport.pin(requests);
    }

    private List<ApiRunSetRow> queryRows(String whereClause, MapSqlParameterSource params) {
        return namedJdbc.query("""
                SELECT s.id, s.collection_id AS collectionId, s.project_id AS projectId,
                       s.env_id AS envId, s.name, s.interruption_mode AS interruptionMode,
                       s.status, s.pinned_json::text AS pinnedJson,
                       s.initial_vars_json::text AS initialVarsJson,
                       s.total_steps AS totalSteps, s.passed_count AS passedCount,
                       s.failed_count AS failedCount, s.skipped_count AS skippedCount
                  FROM tp_apitest_run_set s
                """ + whereClause, params, new BeanPropertyRowMapper<>(ApiRunSetRow.class));
    }

    private static ApiRunSetInfo toInfo(ApiRunSetRow row) {
        return new ApiRunSetInfo(row.getId(), row.getCollectionId(), row.getProjectId(),
                row.getEnvId(), row.getName(), row.getInterruptionMode(), row.getStatus(),
                row.getPinnedJson(), row.getInitialVarsJson(), row.getTotalSteps(),
                row.getPassedCount(), row.getFailedCount(), row.getSkippedCount());
    }
}
