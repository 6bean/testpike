package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.ApiOrchestrationContract;
import com.sixbean.testpike.common.util.ApiWsContract;
import com.sixbean.testpike.common.util.NonUiActionContract;
import com.sixbean.testpike.core.apitest.api.dto.ApiEffectivePreview;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiAssertionFormContract;
import com.sixbean.testpike.core.apitest.domain.component.ApiEffectiveDefaultsResolver;
import com.sixbean.testpike.core.apitest.domain.component.ApiStoredJsonContract;
import com.sixbean.testpike.core.apitest.domain.component.AuthFormContract;
import com.sixbean.testpike.core.apitest.domain.component.ValidatedRequest;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.ApiRequestRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.spec.domain.component.ContractMatchKeyRules;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class ApiRequestServiceImpl implements ApiRequestService {

    private static final int MAX_NAME = 200;
    private static final int MAX_URL_PATH = 1000;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ContractMatchKeyRules matchKeyRules;
    private final ApiEffectiveDefaultsResolver effectiveDefaults;

    public ApiRequestServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                 ContractMatchKeyRules matchKeyRules,
                                 ApiEffectiveDefaultsResolver effectiveDefaults) {
        this.namedJdbc = namedJdbc;
        this.matchKeyRules = matchKeyRules;
        this.effectiveDefaults = effectiveDefaults;
    }

    @Override
    @Audit(objectType = "apitestRequest", action = "create", after = "#result")
    public ApiRequestInfo create(Long operatorId, RequestSaveInput input) {
        if (input == null || input.collectionId() == null || input.collectionId() <= 0) {
            throw new ServiceException("请求必须挂在 API 测试集合下：请先在「执行测试」菜单进入「API 测试」页并选中目标集合，再新建请求", BizErrorCode.VALIDATION_ERROR);
        }
        requireCollection(input.collectionId());
        ValidatedRequest v = validate(input);
        MapSqlParameterSource params = v.toParams(input.collectionId(), operatorId);
        // sort_order = tail append (max+1) so a fresh request never jumps ahead of
        // the user-maintained order .
        Long id = namedJdbc.queryForObject("""
                INSERT INTO tp_apitest_request (collection_id, name, method, url_path,
                    headers_json, body_text, auth_json, assertions_json,
                    contract_endpoint_key, precondition_json, loop_json, protocol,
                    created_by, sort_order)
                VALUES (:collectionId, :name, :method, :urlPath,
                    :headersJson::jsonb, :bodyText, :authJson::jsonb, :assertionsJson::jsonb,
                    :contractEndpointKey, :preconditionJson::jsonb, :loopJson::jsonb, :protocol,
                    :operatorId,
                    (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM tp_apitest_request
                      WHERE collection_id = :collectionId)) RETURNING id
                """, params, Long.class);
        return new ApiRequestInfo(id, input.collectionId(), v.name(), v.method(), v.urlPath(),
                v.headersJson(), v.bodyText(), v.authJson(), v.assertionsJson(), null, false, false,
                v.preconditionJson(), v.loopJson(), v.protocol());
    }

    @Override
    @Audit(objectType = "apitestRequest", action = "update", objectId = "#requestId")
    public ApiRequestInfo update(Long operatorId, long requestId, RequestSaveInput input) {
        ApiRequestRow current = requireRequest(requestId);
        long collectionId = input != null && input.collectionId() != null
                ? input.collectionId() : current.getCollectionId();
        requireCollection(collectionId);
        ValidatedRequest v = validate(input);
        namedJdbc.update("""
                UPDATE tp_apitest_request
                   SET collection_id = :collectionId, name = :name, method = :method,
                       url_path = :urlPath, headers_json = :headersJson::jsonb,
                       body_text = :bodyText, auth_json = :authJson::jsonb,
                       assertions_json = :assertionsJson::jsonb,
                       contract_endpoint_key = :contractEndpointKey,
                       precondition_json = :preconditionJson::jsonb,
                       loop_json = :loopJson::jsonb,
                       protocol = :protocol,
                       contract_stale = false, mock_stale = false, updated_at = now()
                 WHERE id = :id
                """, v.toParams(collectionId, operatorId).addValue("id", requestId));
        return new ApiRequestInfo(requestId, collectionId, v.name(), v.method(), v.urlPath(),
                v.headersJson(), v.bodyText(), v.authJson(), v.assertionsJson(),
                current.getPromotedCaseId(), false, false,
                v.preconditionJson(), v.loopJson(), v.protocol());
    }

    @Override
    @Audit(objectType = "apitestRequest", action = "delete", objectId = "#requestId")
    public void delete(Long operatorId, long requestId) {
        requireRequest(requestId);
        Long runs = namedJdbc.queryForObject(
                "SELECT count(*) FROM tp_apitest_run WHERE request_id = :id",
                new MapSqlParameterSource("id", requestId), Long.class);
        if (runs != null && runs > 0) {
            throw new ServiceException("该请求已有运行记录，为保追溯链不允许直接删除：请在「API 测试」页先清理该请求未升格的运行记录后再删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        namedJdbc.update("DELETE FROM tp_apitest_request WHERE id = :id",
                new MapSqlParameterSource("id", requestId));
    }

    @Override
    @Audit(objectType = "apitestCollection", action = "reorder", objectId = "#collectionId")
    public void reorder(Long operatorId, long collectionId, List<Long> orderedRequestIds) {
        requireCollection(collectionId);
        if (orderedRequestIds == null || orderedRequestIds.isEmpty()) {
            throw new ServiceException("调序必须提交集合内全部请求的完整顺序：请在「API 测试」页对目标集合的请求列表完成调序后保存，勿提交部分顺序",
                    BizErrorCode.VALIDATION_ERROR);
        }
        List<Long> current = namedJdbc.query("""
                SELECT id FROM tp_apitest_request WHERE collection_id = :collectionId
                """, new MapSqlParameterSource("collectionId", collectionId),
                (rs, n) -> rs.getLong(1));
        // Honest full-set check: a stale page (member added/removed meanwhile) or a
        // foreign id must be named, not silently dropped or reordered into another set.
        if (orderedRequestIds.size() != new HashSet<>(orderedRequestIds).size()) {
            throw new ServiceException("调序列表存在重复请求，与集合成员不一致：请在「API 测试」页刷新列表后重新提交调序",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Set<Long> currentIds = new HashSet<>(current);
        if (!currentIds.equals(new HashSet<>(orderedRequestIds))) {
            throw new ServiceException("集合成员已变更，调序列表与当前成员不一致：请在「API 测试」页刷新后重新发起调序",
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (int i = 0; i < orderedRequestIds.size(); i++) {
            namedJdbc.update("UPDATE tp_apitest_request SET sort_order = :pos, updated_at = now()"
                            + " WHERE id = :id",
                    new MapSqlParameterSource("pos", i + 1).addValue("id", orderedRequestIds.get(i)));
        }
    }

    @Override
    public List<ApiRequestInfo> list(long collectionId) {
        return queryRows(" WHERE collection_id = :collectionId ORDER BY sort_order, id",
                new MapSqlParameterSource("collectionId", collectionId));
    }

    @Override
    public ApiRequestInfo get(long requestId) {
        return queryRows(" WHERE id = :id", new MapSqlParameterSource("id", requestId))
                .stream().findFirst()
                .orElseThrow(() -> new ServiceException("API 请求不存在或已删除：请在「API 测试」页刷新列表并重新选择目标请求",
                        BizErrorCode.NOT_FOUND));
    }

    private List<ApiRequestInfo> queryRows(String whereClause, MapSqlParameterSource params) {
        List<ApiRequestRow> rows = namedJdbc.query("""
                SELECT id, collection_id AS collectionId, name, method, url_path AS urlPath,
                       headers_json::text AS headersJson, body_text AS bodyText,
                       auth_json::text AS authJson, assertions_json::text AS assertionsJson,
                       precondition_json::text AS preconditionJson,
                       loop_json::text AS loopJson,
                       protocol,
                       promoted_case_id AS promotedCaseId, contract_stale AS contractStale,
                       mock_stale AS mockStale
                  FROM tp_apitest_request
                """ + whereClause, params, new BeanPropertyRowMapper<>(ApiRequestRow.class));
        return rows.stream().map(row -> new ApiRequestInfo(row.getId(), row.getCollectionId(),
                row.getName(), row.getMethod(), row.getUrlPath(), row.getHeadersJson(),
                row.getBodyText(), row.getAuthJson(), row.getAssertionsJson(),
                row.getPromotedCaseId(), row.isContractStale(), row.isMockStale(),
                row.getPreconditionJson(), row.getLoopJson(), row.getProtocol())).toList();
    }

    @Override
    public ApiEffectivePreview effectivePreview(long requestId) {
        ApiRequestInfo req = get(requestId);
        // One round-trip for the owning collection's name and stored defaults —
        // the same columns the dispatch envelope anchor reads .
        CollectionDefaults defaults = loadCollectionDefaults(req.collectionId());
        return mergeWith(defaults, req);
    }

    @Override
    public List<ApiEffectivePreview> effectivePreviewBatch(long collectionId) {
        // Existence gate first: an unknown collection is a 404 with a human
        // message, never a silent empty batch (same posture as list()'s peers).
        CollectionDefaults defaults = loadCollectionDefaults(collectionId);
        // Single round trip for member rows, then row-for-row the SAME merge
        // helper the per-request face uses ; red line 6 — the
        // batch must never grow a second interpretation).
        return list(collectionId).stream().map(req -> mergeWith(defaults, req)).toList();
    }

    /**
     * Owning collection's name and stored default blocks in one read; the same
     * columns the dispatch envelope anchor consumes. 404 when absent (batch
     * face leans on this as its existence gate too).
     */
    private CollectionDefaults loadCollectionDefaults(long collectionId) {
        try {
            Map<String, Object> collection = namedJdbc.queryForMap("""
                    SELECT name, headers_json::text AS headersJson, auth_json::text AS authJson
                      FROM tp_apitest_collection WHERE id = :id
                    """, new MapSqlParameterSource("id", collectionId));
            return new CollectionDefaults(String.valueOf(collection.get("name")),
                    String.valueOf(collection.get("headersJson")),
                    String.valueOf(collection.get("authJson")));
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            throw new ServiceException("接口集合不存在或已删除：请在「API 测试」页刷新后重新选择目标集合",
                    BizErrorCode.NOT_FOUND);
        }
    }

    /** Merge one request row with the collection defaults (single verdict point). */
    private ApiEffectivePreview mergeWith(CollectionDefaults defaults, ApiRequestInfo req) {
        JsonNode requestStored = effectiveDefaults.parseStored(req.headersJson());
        JsonNode collectionStored = effectiveDefaults.parseStored(defaults.headersJson());
        ApiEffectiveDefaultsResolver.AuthResolution auth =
                effectiveDefaults.resolveAuth(effectiveDefaults.parseStored(req.authJson()),
                        effectiveDefaults.parseStored(defaults.authJson()));
        String mergedHeaders = effectiveDefaults
                .mergedHeadersObject(requestStored, collectionStored).toString();
        return new ApiEffectivePreview(req.id(), req.collectionId(), defaults.name(),
                effectiveDefaults.headerRows(requestStored, collectionStored),
                mergedHeaders, auth.block().toString(), auth.source());
    }

    /** Collection name plus the two stored default blocks (jsonb text form). */
    private record CollectionDefaults(String name, String headersJson, String authJson) {
    }

    @Override
    public int markContractStale(long projectId, Set<String> endpointKeys) {
        if (endpointKeys == null || endpointKeys.isEmpty()) {
            return 0;
        }
        Set<String> canonical = new HashSet<>();
        for (String key : endpointKeys) {
            canonical.add(matchKeyRules.matchKeyFromLoose(key));
        }
        // Closure by equality on the first-class column (indexed) — the same join the
        // inventory page and the change-analysis queue use; no per-row recompute.
        Integer marked = namedJdbc.queryForObject("""
                WITH hit AS (
                    UPDATE tp_apitest_request SET contract_stale = true, updated_at = now()
                     WHERE contract_stale = false
                       AND contract_endpoint_key IN (:keys)
                       AND collection_id IN (SELECT id FROM tp_apitest_collection
                                              WHERE project_id = :projectId)
                    RETURNING id
                )
                SELECT count(*) FROM hit
                """, new MapSqlParameterSource("keys", canonical).addValue("projectId", projectId),
                Integer.class);
        return marked == null ? 0 : marked;
    }

    /** Derived-stub scope query: endpoint keys of openapi-sourced rules living in
     * this project's mock spaces — the twin badge only fires where a contract-
     * derived stub actually exists (subset of the contract badge, never wider). */
    private static final String DERIVED_STUB_KEYS_SQL = """
            SELECT st.method, st.path_pattern FROM tp_mock_stub st
              JOIN tp_mock_space sp ON st.space_id = sp.id
             WHERE sp.project_id = :projectId AND st.source = 'openapi'
            """;

    @Override
    public int markMockStale(long projectId, Set<String> endpointKeys) {
        if (endpointKeys == null || endpointKeys.isEmpty()) {
            return 0;
        }
        Set<String> canonical = new HashSet<>();
        for (String key : endpointKeys) {
            canonical.add(matchKeyRules.matchKeyFromLoose(key));
        }
        Set<String> derived = new HashSet<>();
        namedJdbc.query(DERIVED_STUB_KEYS_SQL,
                new MapSqlParameterSource("projectId", projectId),
                rs -> {
                    derived.add(matchKeyRules.matchKey(
                            rs.getString(1), rs.getString(2)));
                });
        derived.retainAll(canonical);
        if (derived.isEmpty()) {
            return 0;
        }
        List<ApiRequestInfo> candidates = queryRows("""
                 WHERE mock_stale = false
                   AND collection_id IN (SELECT id FROM tp_apitest_collection
                                          WHERE project_id = :projectId)
                """, new MapSqlParameterSource("projectId", projectId));
        int marked = 0;
        for (ApiRequestInfo req : candidates) {
            if (derived.contains(matchKeyRules.matchKey(req.method(), req.urlPath()))) {
                namedJdbc.update("UPDATE tp_apitest_request SET mock_stale = true,"
                                + " updated_at = now() WHERE id = :id",
                        new MapSqlParameterSource("id", req.id()));
                marked++;
            }
        }
        return marked;
    }

    private ApiRequestRow requireRequest(long id) {
        List<ApiRequestRow> rows = namedJdbc.query("""
                SELECT id, collection_id AS collectionId, name, method, url_path AS urlPath,
                       headers_json::text AS headersJson, body_text AS bodyText,
                       auth_json::text AS authJson, assertions_json::text AS assertionsJson,
                       promoted_case_id AS promotedCaseId, contract_stale AS contractStale
                  FROM tp_apitest_request WHERE id = :id
                """, new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(ApiRequestRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("API 请求不存在或已删除：请在「API 测试」页刷新列表并重新选择目标请求", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private void requireCollection(long id) {
        Long count = namedJdbc.queryForObject(
                "SELECT count(*) FROM tp_apitest_collection WHERE id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        if (count == null || count == 0) {
            throw new ServiceException("API 测试集合不存在或已删除：请在「API 测试」页刷新后重新选择目标集合", BizErrorCode.NOT_FOUND);
        }
    }

    
    private ValidatedRequest validate(RequestSaveInput input) {
        String name = input.name() == null ? "" : input.name().trim();
        if (name.isEmpty()) {
            throw new ServiceException("请求名称不能为空：请在「API 测试」页的请求表单填写名称后再保存", BizErrorCode.VALIDATION_ERROR);
        }
        if (name.length() > MAX_NAME) {
            throw new ServiceException("请求名称最长 " + MAX_NAME + " 字：请缩短名称，可在「API 测试」页的请求表单重新填写", BizErrorCode.VALIDATION_ERROR);
        }
        String method = input.method() == null ? "" : input.method().trim()
                .toUpperCase(Locale.ROOT);
        if (!NonUiActionContract.HTTP_METHODS.contains(method)) {
            throw new ServiceException("HTTP 动词不合法：仅支持 "
                    + String.join(" / ", NonUiActionContract.HTTP_METHODS.stream().sorted().toList())
                    + "；正确写法：从上述列表中选择，当前值 " + method
                    + "，请在「API 测试」页的请求表单修改方法后保存", BizErrorCode.VALIDATION_ERROR);
        }
        validateUrlPath(input.urlPath());
        String headersJson = normalizeJson(input.headersJson(), "[]", "请求头", true);
        String authJson = normalizeJson(input.authJson(), "{}", "认证配置", false);
        // W1c auth presets: shape + secret-reference gate, single source AuthFormContract
        // (executor _auth_headers accepts the same five types — no second dialect).
        String authViolation = AuthFormContract.violation(readAuthNode(authJson));
        if (authViolation != null) {
            throw new ServiceException(authViolation, BizErrorCode.VALIDATION_ERROR);
        }
        String assertionsJson = normalizeJson(input.assertionsJson(), "[]", "断言", true);
        // transport discriminator resolved BEFORE the assertion gate —
        // the grammar is protocol-crossed (http face = six types, ws face = three
        // ws.* types, single source ApiWsContract). Blank = http default (legacy rows
        // keep exact behavior); unknown values fail closed naming the offender.
        String protocol = ApiWsContract.normalize(input.protocol());
        if (protocol == null) {
            throw new ServiceException("传输协议不合法：正确写法为「http」或「ws」，当前值 "
                    + input.protocol() + "，请在「API 测试」页的请求表单修改协议后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
        validateAssertionRows(assertionsJson, protocol);
        // orchestration gate: precondition/loop forms are validated by
        // the single-source vocabulary contract (same posture as the assertion gate;
        // no second dialect). Absent = '{}' (always run / straight-line).
        String preconditionJson = normalizeJson(input.preconditionJson(), "{}", "前置条件", false);
        String preconditionViolation =
                ApiOrchestrationContract.preconditionViolation(readNode(preconditionJson, "前置条件"));
        if (preconditionViolation != null) {
            throw new ServiceException(preconditionViolation, BizErrorCode.VALIDATION_ERROR);
        }
        String loopJson = normalizeJson(input.loopJson(), "{}", "循环配置", false);
        String loopViolation = ApiOrchestrationContract.loopViolation(readNode(loopJson, "循环配置"));
        if (loopViolation != null) {
            throw new ServiceException(loopViolation, BizErrorCode.VALIDATION_ERROR);
        }
        String bodyText = input.bodyText() == null ? "" : input.bodyText();
        // Normalized contract match key folded once at the save-chain single point
        // (create + update share this path); concrete-id paths and contract templates
        // land on the same key so joins and badge closure hit it by equality.
        String contractEndpointKey = matchKeyRules.matchKey(method, input.urlPath().trim());
        return new ValidatedRequest(name, method, input.urlPath().trim(), headersJson,
                bodyText, authJson, assertionsJson, contractEndpointKey,
                preconditionJson, loopJson, protocol);
    }

    private void validateUrlPath(String raw) {
        String path = raw == null ? "" : raw.trim();
        if (path.isEmpty()) {
            throw new ServiceException("请求 URL 路径不能为空：请在「API 测试」页的请求表单填写以 / 开头的路径（示例 /api/v1/orders）", BizErrorCode.VALIDATION_ERROR);
        }
        if (path.contains("://") || path.startsWith("//") || !path.startsWith("/")) {
            throw new ServiceException("请求 URL 只能填路径段（以 / 开头，示例 /api/v1/orders），当前值 "
                    + path + "；域名请在「API 测试」页选择环境档案决定，勿写绝对地址",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (path.length() > MAX_URL_PATH) {
            throw new ServiceException("请求 URL 路径最长 " + MAX_URL_PATH + " 字：请在「API 测试」页的请求表单缩短路径后保存",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    private String normalizeJson(String raw, String fallback, String field, boolean wantArray) {
        // Delegation : shared column-shape gate, same verdicts as before —
        // collection face and request face must not carry two copies of this logic.
        return ApiStoredJsonContract.normalize(raw, fallback, field, wantArray);
    }

    /** Parse the normalized auth object for the shape gate (normalizeJson already guarantees JSON). */
    private JsonNode readAuthNode(String authJson) {
        return readNode(authJson, "认证配置");
    }

    /** Re-parse a normalizeJson-passed document for the contract gates (shape guaranteed valid). */
    private JsonNode readNode(String json, String field) {
        // Delegation : shared re-parse gate with the collection face.
        return ApiStoredJsonContract.read(json, field);
    }

    /** Assertion row gate — verdicts and texts stay single-source in
 * ApiAssertionFormContract (S3 builder kernel, no second copy).
 * Transport-crossed grammar : http face = six types, ws face = the
 * three ws.* types; the protocol vocabulary stays single-source in ApiWsContract. */
    private void validateAssertionRows(String assertionsJson, String protocol) {
        JsonNode rows;
        try {
            rows = MAPPER.readTree(assertionsJson);
        } catch (Exception e) {
            throw new ServiceException("断言不是合法 JSON：请在「API 测试」页的请求表单按行填写断言（正确写法为数组结构，JSON 由系统生成）", BizErrorCode.VALIDATION_ERROR);
        }
        for (JsonNode row : rows) {
            String violation = ApiAssertionFormContract.rowViolation(row, protocol);
            if (violation != null) {
                throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
            }
        }
    }
}
