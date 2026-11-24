package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.core.apitest.domain.component.ApiRunRefusals;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.api.dto.ApiRunInfo;
import com.sixbean.testpike.core.apitest.api.dto.ResponseCompareView;
import com.sixbean.testpike.core.apitest.api.dto.RunCreateInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiTestEnvelopeAssembler;
import com.sixbean.testpike.core.apitest.domain.component.ApiTestEnvGuard;
import com.sixbean.testpike.core.apitest.domain.component.ApiVarPoolSupport;
import com.sixbean.testpike.core.apitest.domain.component.ResponseCompareMode;
import com.sixbean.testpike.core.apitest.domain.component.ResponseCompareSupport;
import com.sixbean.testpike.core.apitest.domain.component.ResponseDiffEntry;
import com.sixbean.testpike.core.apitest.domain.component.ResponseDiffKernel;
import com.sixbean.testpike.core.apitest.domain.component.ResponseSnapshotBody;
import com.sixbean.testpike.core.apitest.domain.service.ApiRunService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.ApiRunRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.domain.component.ExecStepSnapshotSupport;
import com.sixbean.testpike.core.executor.domain.service.ExecTaskService;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class ApiRunServiceImpl implements ApiRunService {

    private static final Set<Integer> RUN_STATUSES = Set.of(0, 1, 2, 3, 4);
    private static final int MAX_ARTIFACT_REF = 500;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ApiTestEnvelopeAssembler envelopeAssembler;
    private final ApiVarPoolSupport apiVarPoolSupport;
    private final ApiTestEnvGuard envGuard;
    private final ExecTaskService execTaskService;
    private final ExecStepSnapshotSupport execStepSnapshotSupport;

    public ApiRunServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                             ApiTestEnvelopeAssembler envelopeAssembler,
                             ApiVarPoolSupport apiVarPoolSupport,
                             ApiTestEnvGuard envGuard,
                             @Lazy ExecTaskService execTaskService,
                             ExecStepSnapshotSupport execStepSnapshotSupport) {
        this.namedJdbc = namedJdbc;
        this.envelopeAssembler = envelopeAssembler;
        this.apiVarPoolSupport = apiVarPoolSupport;
        this.envGuard = envGuard;
        this.execTaskService = execTaskService;
        this.execStepSnapshotSupport = execStepSnapshotSupport;
    }

    @Override
    @Transactional
    @Audit(objectType = "apitestRun", action = "create", after = "#result")
    public ApiRunInfo create(Long operatorId, RunCreateInput input) {
        if (input == null || input.requestId() == null || input.requestId() <= 0) {
            throw new ServiceException(ApiRunRefusals.runRequestMissing(
                    input == null ? null : input.requestId()), BizErrorCode.VALIDATION_ERROR);
        }
        Long projectScoped = requestProject(input.requestId());
        Long envId = input.envId();
        // Host-whitelist precondition guard (SSRF red line) now shared with
        // collection runs via ApiTestEnvGuard — single source, same message.
        envGuard.requireSameProject(envId, projectScoped);
        Long id = namedJdbc.queryForObject("""
                INSERT INTO tp_apitest_run (request_id, env_id, status, created_by)
                VALUES (:requestId, :envId, 0, :operatorId) RETURNING id
                """, new MapSqlParameterSource()
                .addValue("requestId", input.requestId())
                .addValue("envId", envId)
                .addValue("operatorId", operatorId), Long.class);
        if (envId != null) {
            ApiTestEnvelopeAssembler.DispatchPayload payload = envelopeAssembler.assemble(id);
            execTaskService.createApiTestRun(payload.projectId(), payload.envelopeJson());
        }
        return new ApiRunInfo(id, input.requestId(), envId, 0, null, "[]", "", "");
    }

    @Override
    public ApiRunInfo get(long runId) {
        List<ApiRunRow> rows = queryRows(" WHERE r.id = :id", new MapSqlParameterSource("id", runId));
        return rows.stream().findFirst()
                .map(ApiRunServiceImpl::toInfo)
                .orElseThrow(() -> new ServiceException(ApiRunRefusals.runRecordMissing(runId), BizErrorCode.NOT_FOUND));
    }

    @Override
    public List<ApiRunInfo> listByRequest(long requestId) {
        return queryRows(" WHERE r.request_id = :requestId ORDER BY r.created_at DESC, r.id DESC",
                new MapSqlParameterSource("requestId", requestId))
                .stream().map(ApiRunServiceImpl::toInfo).toList();
    }

    @Override
    @Audit(objectType = "apitestRun", action = "result", objectId = "#runId")
    public ApiRunInfo recordResult(long runId, int status, Long durationMs, String assertDetailJson,
                                   String artifactRef, String errorMsg) {
        return doRecordResult(runId, status, durationMs, assertDetailJson, artifactRef, errorMsg, null);
    }

    @Override
    @Audit(objectType = "apitestRun", action = "result", objectId = "#runId")
    public ApiRunInfo recordResult(long runId, int status, Long durationMs, String assertDetailJson,
                                   String artifactRef, String errorMsg, String storedVarsJson) {
        return doRecordResult(runId, status, durationMs, assertDetailJson, artifactRef,
                errorMsg, storedVarsJson);
    }

    private ApiRunInfo doRecordResult(long runId, int status, Long durationMs,
                                      String assertDetailJson, String artifactRef,
                                      String errorMsg, String storedVarsJson) {
        if (!RUN_STATUSES.contains(status)) {
            throw new ServiceException(ApiRunRefusals.statusUnsupported(status),
                    BizErrorCode.VALIDATION_ERROR);
        }
        get(runId);
        String detail = normalizeDetail(assertDetailJson);
        String ref = artifactRef == null ? "" : artifactRef.trim();
        if (ref.length() > MAX_ARTIFACT_REF) {
            throw new ServiceException(ApiRunRefusals.artifactRefTooLong(MAX_ARTIFACT_REF,
                    ref.length()), BizErrorCode.VALIDATION_ERROR);
        }
        namedJdbc.update("""
                UPDATE tp_apitest_run
                   SET status = :status, duration_ms = :durationMs,
                       assert_detail = :detail::jsonb, artifact_ref = :ref,
                       error_msg = :errorMsg
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("durationMs", durationMs)
                .addValue("detail", detail)
                .addValue("ref", ref)
                .addValue("errorMsg", errorMsg == null ? "" : errorMsg)
                .addValue("id", runId));
        if (storedVarsJson != null && !storedVarsJson.isBlank() && !"{}".equals(storedVarsJson)) {
            Map<String, Object> anchor = namedJdbc.queryForMap("""
                    SELECT c.id AS collectionId, c.project_id AS projectId
                      FROM tp_apitest_run r
                      JOIN tp_apitest_request q ON q.id = r.request_id
                      JOIN tp_apitest_collection c ON c.id = q.collection_id
                     WHERE r.id = :runId
                    """, new MapSqlParameterSource("runId", runId));
            String collectionPart = apiVarPoolSupport.collectionPart(storedVarsJson);
            if (!"{}".equals(collectionPart)) {
                namedJdbc.update("""
                        UPDATE tp_apitest_collection
                           SET vars_json = vars_json || :vars::jsonb, updated_at = now()
                         WHERE id = :collectionId
                        """, new MapSqlParameterSource()
                        .addValue("vars", collectionPart)
                        .addValue("collectionId", anchor.get("collectionId")));
            }
            apiVarPoolSupport.globalPart(storedVarsJson).forEach((key, storedValue) ->
                    namedJdbc.update("""
                            INSERT INTO tp_apitest_global_var (project_id, var_key, var_value)
                            VALUES (:projectId, :key, :value)
                            ON CONFLICT (project_id, var_key)
                            DO UPDATE SET var_value = EXCLUDED.var_value, updated_at = now()
                            """, new MapSqlParameterSource()
                            .addValue("projectId", anchor.get("projectId"))
                            .addValue("key", key)
                            .addValue("value", storedValue)));
        }
        return get(runId);
    }

    @Override
    @Audit(objectType = "apitestRun", action = "delete", objectId = "#runId")
    public void delete(Long operatorId, long runId) {
        get(runId);
        namedJdbc.update("DELETE FROM tp_apitest_run WHERE id = :id",
                new MapSqlParameterSource("id", runId));
    }

    @Override
    public void clearUnpromoted(long requestId) {
        namedJdbc.update("""
                DELETE FROM tp_apitest_run
                 WHERE request_id = :requestId
                   AND NOT EXISTS (SELECT 1 FROM tp_apitest_request q
                                    WHERE q.id = tp_apitest_run.request_id
                                      AND q.promoted_case_id IS NOT NULL)
                """, new MapSqlParameterSource("requestId", requestId));
    }

    @Override
    public ResponseCompareView compare(long leftRunId, long rightRunId) {
        // Existence first (404 reuses get()'s single verdict vocabulary), then
        // honest snapshot availability — a run that never dispatched or that
        // recorded no step-1 response is "nothing to compare" (400), never a
        // fabricated empty diff.
        get(leftRunId);
        get(rightRunId);
        ResponseSnapshotBody left = readResponseBody(leftRunId);
        ResponseSnapshotBody right = readResponseBody(rightRunId);

        ResponseCompareMode mode = ResponseCompareSupport.detectMode(left.body(), right.body());
        String leftSha = DigestHex.sha256(left.body() == null ? "" : left.body());
        String rightSha = DigestHex.sha256(right.body() == null ? "" : right.body());
        List<ResponseDiffEntry> entries = switch (mode) {
            case JSON -> ResponseDiffKernel.diff(left.body(), right.body());
            case TEXT -> ResponseCompareSupport.textLineDiff(left.body(), right.body());
            case BINARY -> List.of();
        };
        return new ResponseCompareView(mode, left.truncated(), right.truncated(),
                leftSha, rightSha, entries);
    }

    /**
     * Read path: run → the newest task_type=4 dispatch row (reverse lookup on
     * action_json.runId, the same key ApiRunResultFinalizer writes) → step-1
     * response snapshot through ExecStepSnapshotSupport.listSnapshots (shared
     * gunzip path, no second decompression implementation).
     */
    private ResponseSnapshotBody readResponseBody(long runId) {
        List<Long> taskIds = namedJdbc.query("""
                SELECT id FROM tp_exec_task
                 WHERE task_type = 4
                   AND action_json::jsonb ->> 'runId' = :runId
                 ORDER BY id DESC LIMIT 1
                """, new MapSqlParameterSource("runId", String.valueOf(runId)),
                (rs, rowNum) -> rs.getLong(1));
        if (taskIds.isEmpty()) {
            throw new ServiceException(ApiRunRefusals.noDispatchSnapshot(runId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        ExecStepSnapshotView step = execStepSnapshotSupport.listSnapshots(taskIds.get(0))
                .stream()
                .filter(snapshot -> snapshot.stepNo() == 1)
                .findFirst()
                .orElseThrow(() -> new ServiceException(
                        ApiRunRefusals.missingResponseRecord(runId),
                        BizErrorCode.VALIDATION_ERROR));
        return new ResponseSnapshotBody(extractBody(runId, step), step.truncated());
    }

    /**
     * response column shape = {status,url,headers,body} (collected by the
     * executor's api_runner); the body field is the compare input.
     * <p>The write path runs bodies through SnapshotJsonSanitizer, which parses
     * a JSON string body into structured storage (searchable, TC-V1-01). On
     * read-back a container node's asText() is empty, so the node must be
     * re-serialized to JSON text before the tri-state decision — otherwise
     * structured JSON is misclassified into the TEXT lane (false negative).
     * Scalar string bodies keep their original text.
     */
    private String extractBody(long runId, ExecStepSnapshotView snapshot) {
        String response = snapshot.response();
        if (response == null || response.isBlank()) {
            throw new ServiceException(ApiRunRefusals.missingResponseRecord(runId),
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            JsonNode body = MAPPER.readTree(response).path("body");
            if (body.isMissingNode() || body.isNull()) {
                return "";
            }
            if (body.isObject() || body.isArray()) {
                return MAPPER.writeValueAsString(body);
            }
            return body.asText();
        } catch (Exception e) {
            throw new ServiceException(ApiRunRefusals.invalidSnapshotBody(e.getMessage()),
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private Long requestProject(long requestId) {
        List<Long> projects = namedJdbc.query("""
                SELECT c.project_id FROM tp_apitest_request r
                  JOIN tp_apitest_collection c ON c.id = r.collection_id
                 WHERE r.id = :id
                """, new MapSqlParameterSource("id", requestId),
                (rs, rowNum) -> rs.getLong(1));
        if (projects.isEmpty()) {
            throw new ServiceException(ApiRunRefusals.requestMissing(requestId), BizErrorCode.NOT_FOUND);
        }
        return projects.get(0);
    }

    
    private String normalizeDetail(String raw) {
        if (raw == null || raw.isBlank()) {
            return "[]";
        }
        JsonNode node;
        try {
            node = MAPPER.readTree(raw);
        } catch (Exception e) {
            throw new ServiceException(ApiRunRefusals.assertDetailNotJson(raw),
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (!node.isArray()) {
            throw new ServiceException(ApiRunRefusals.assertDetailNotArray(raw),
                    BizErrorCode.VALIDATION_ERROR);
        }
        return node.toString();
    }

    private List<ApiRunRow> queryRows(String whereClause, MapSqlParameterSource params) {
        return namedJdbc.query("""
                SELECT r.id, r.request_id AS requestId, r.env_id AS envId, r.status,
                       r.duration_ms AS durationMs, r.assert_detail::text AS assertDetail,
                       r.artifact_ref AS artifactRef, r.error_msg AS errorMsg
                  FROM tp_apitest_run r
                """ + whereClause, params, new BeanPropertyRowMapper<>(ApiRunRow.class));
    }

    private static ApiRunInfo toInfo(ApiRunRow row) {
        return new ApiRunInfo(row.getId(), row.getRequestId(), row.getEnvId(), row.getStatus(),
                row.getDurationMs(), row.getAssertDetail(), row.getArtifactRef(), row.getErrorMsg());
    }
}
