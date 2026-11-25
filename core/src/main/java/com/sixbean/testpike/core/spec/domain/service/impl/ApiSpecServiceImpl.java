package com.sixbean.testpike.core.spec.domain.service.impl;

import com.alibaba.fastjson2.JSON;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.component.dedup.DedupGate;
import com.sixbean.testpike.core.common.component.dedup.DedupVerdict;
import com.sixbean.testpike.core.common.domain.component.DigestHex;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.domain.component.ApiEndpoint;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.spec.domain.component.SpecDiff;
import com.sixbean.testpike.core.spec.domain.component.SpecUrlFetcher;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecChangeAnalysisService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;


@Service
public class ApiSpecServiceImpl implements ApiSpecService {

    private static final Logger log = LoggerFactory.getLogger(ApiSpecServiceImpl.class);

    
    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, document_id, name, spec_format, spec_title,
                   content_sha256, spec_version, endpoints::text AS endpoints_text,
                   prev_spec_id, diff_summary::text AS diff_text,
                   created_by, created_at
            FROM tp_api_spec
            """;

    private final NamedParameterJdbcTemplate named;
    private final OpenApiSpecParser parser;
    private final SpecUrlFetcher urlFetcher;
    private final ApiRequestService apiRequestService;
    private final ApiSpecChangeAnalysisService changeAnalysis;
    private final DedupGate dedupGate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
            objectChangedEvents;

    public ApiSpecServiceImpl(NamedParameterJdbcTemplate named, OpenApiSpecParser parser,
                              SpecUrlFetcher urlFetcher, ApiRequestService apiRequestService,
                              ApiSpecChangeAnalysisService changeAnalysis, DedupGate dedupGate,
                              com.sixbean.testpike.core.linkage.domain.service.ObjectChangedEventPublisher
                                      objectChangedEvents) {
        this.named = named;
        this.parser = parser;
        this.urlFetcher = urlFetcher;
        this.apiRequestService = apiRequestService;
        this.changeAnalysis = changeAnalysis;
        this.dedupGate = dedupGate;
        this.objectChangedEvents = objectChangedEvents;
    }

    @Override
    @Audit(objectType = "api_spec", action = "spec_upload", objectId = "#result.id",
            after = "#result")
    @Transactional
    public ApiSpecInfo upload(long operatorId, long projectId, String name,
                              byte[] content, Long documentId) {
        return uploadInternal(operatorId, projectId, name, content, documentId, "upload");
    }

    /** Upload core (shared single source for upload and fetchFromUrl — no copy;
     *  audit advice only on the outward-facing methods). source=upload|fetch: a
     *  non-empty endpoint diff lands one change-analysis queue row per (prev,id)
     *  pair in the same transaction — subscriptions reach the same hook through
     *  fetchFromUrl, zero new channel. */
    private ApiSpecInfo uploadInternal(long operatorId, long projectId, String name,
                                       byte[] content, Long documentId, String source) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            throw new ServiceException("契约名称不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        if (content == null || content.length == 0) {
            throw new ServiceException("契约文件内容为空，请检查上传文件",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (content.length > MAX_BYTES) {
            throw new ServiceException("契约文件超过大小上限 5MB", BizErrorCode.VALIDATION_ERROR);
        }
        String format = detectFormat(content);
        ParsedApiSpec parsed = parser.parse(content, format);
        String sha256 = DigestHex.sha256(content);

        // Duplicate-definition gate (design A7 business key): every endpoint
        // this contract defines must not already live under a different spec
        // name in the project. The same-name version chain is exempt —
        // re-uploading the owning contract is a new version, not a duplicate.
        for (ApiEndpoint ep : parsed.endpoints()) {
            DedupVerdict verdict = dedupGate.evaluateApiEndpoint(projectId,
                    ep.method(), ep.path(), trimmedName);
            if (verdict.blocksCreate()) {
                throw new ServiceException(String.format(
                        "接口 %s %s 已由契约《%s》(id=%d) 定义，重复定义被检出拦截；"
                                + "如需更新请在原契约上传新版本，如确属不同接口请调整方法或路径。"
                                + "该拦截可在「系统设置 → 系统配置」将 ai.dedup.enabled 设为关闭（修改即时生效）",
                        ep.method(), ep.path(), verdict.matched().title(), verdict.matched().id()),
                        BizErrorCode.VALIDATION_ERROR);
            }
        }

        ApiSpecInfo latest = findLatest(projectId, trimmedName);
        if (latest != null && sha256.equals(latest.contentSha256())) {
            throw new ServiceException("契约与现有版本内容一致（" + trimmedName
                    + " v" + latest.specVersion() + "），无需重复上传", BizErrorCode.RESOURCE_CONFLICT);
        }

        SpecDiff diff = latest == null ? null : diffOf(parseEndpoints(latest), parsed);
        long id = insert(projectId, documentId, trimmedName, format, parsed, sha256,
                latest == null ? 1 : latest.specVersion() + 1,
                latest == null ? null : latest.id(), diff, operatorId,
                new String(content, StandardCharsets.UTF_8));
        if (diff != null && !diff.isEmpty()) {
            markImportedRequestsStale(projectId, diff);
            // Change-analysis queue: same non-empty diff lands one review row per
            // (prev,id) pair in this transaction. A queue write failure must never
            // roll back the version row itself (badge semantics), so it is guarded
            // and only warns — re-analysis is reachable via the explicit endpoint.
            try {
                changeAnalysis.analyze(operatorId, id, source);
            } catch (RuntimeException e) {
                log.warn("契约变更分析队列落行失败（specId={}）: {}", id, e.getMessage());
            }
            // Linkage bus announcement, same gate as the stale badge and the
            // analysis queue: only a real endpoint diff arms downstream edges.
            // First-version uploads (diff == null) have no imported assets yet,
            // so nothing fans out. changedFields stays null = arm every edge
            // registered for api_spec (the ledger triggers are key-set based,
            // not field based). The publisher defers to afterCommit.
            objectChangedEvents.publish(
                    new com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventMessage(
                            com.sixbean.testpike.core.linkage.api.dto.ObjectChangedEventType
                                    .API_SPEC.name(),
                            id, projectId, operatorId, latest.specVersion() + 1,
                            null, System.currentTimeMillis()));
        }
        return get(id);
    }

    /**
 * <p> adds the mock twin badge on the very same key set: requests
 * that additionally have a contract-derived stub in the project get
 * mock_stale (subset scope, zero second judgment source — SpecDiff stays the
 * only diff spec). Guarded separately so one badge failure never blocks the
 * other or the upload main chain.
 */
    private void markImportedRequestsStale(long projectId, SpecDiff diff) {
        Set<String> keys = new HashSet<>(diff.changed());
        keys.addAll(diff.removed());
        try {
            apiRequestService.markContractStale(projectId, keys);
        } catch (RuntimeException e) {
            log.warn("契约变更徽标挂标失败（projectId={}）: {}", projectId, e.getMessage());
        }
        try {
            apiRequestService.markMockStale(projectId, keys);
        } catch (RuntimeException e) {
            log.warn("Mock 派生徽标挂标失败（projectId={}）: {}", projectId, e.getMessage());
        }
    }

    
    @Override
    @Audit(objectType = "api_spec", action = "spec_fetch", objectId = "#result.id",
            after = "#result")
    @Transactional
    public ApiSpecInfo fetchFromUrl(long operatorId, long projectId, String name, String url) {
        byte[] content = urlFetcher.fetch(url, MAX_BYTES);
        return uploadInternal(operatorId, projectId, name, content, null, "fetch");
    }

    /**
 * set-internal pull: read the provider project's LATEST spec_text row for
 * (providerProjectId, providerSpecName) and feed those bytes into the same
 * uploadInternal core — parse/SHA256 idempotence/version-chain diff stay single
 * source, zero second channel. source tag "program" marks the change-analysis row.
 */
    @Override
    @Audit(objectType = "api_spec", action = "spec_fetch", objectId = "#result.id",
            after = "#result")
    @Transactional
    public ApiSpecInfo fetchFromProviderSpec(long operatorId, long projectId, String name,
                                             long providerProjectId, String providerSpecName) {
        List<String> texts = named.queryForList("""
                SELECT spec_text FROM tp_api_spec
                WHERE project_id = :pid AND name = :name
                ORDER BY spec_version DESC LIMIT 1
                """, new MapSqlParameterSource("pid", providerProjectId)
                .addValue("name", providerSpecName.trim()), String.class);
        if (texts.isEmpty()) {
            throw new ServiceException("提供方项目内没有契约「" + providerSpecName
                    + "」，订阅源已失效", BizErrorCode.NOT_FOUND);
        }
        return uploadInternal(operatorId, projectId, name,
                texts.get(0).getBytes(StandardCharsets.UTF_8), null, "program");
    }

    @Override
    public ApiSpecInfo get(long id) {
        List<ApiSpecInfo> rows = query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", id));
        if (rows.isEmpty()) {
            throw new ServiceException("契约版本不存在: " + id, BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public List<ApiSpecInfo> history(long projectId, String name) {
        return query(SELECT_COLUMNS + " WHERE project_id = :pid AND name = :name"
                        + " ORDER BY spec_version ASC",
                new MapSqlParameterSource("pid", projectId).addValue("name", name.trim()));
    }

    @Override
    public PageResult<ApiSpecInfo> list(long projectId, int page, int size) {
        int safeSize = PageQueryPolicy.enforceSize(size);
        int safePage = Math.max(page, 1);
        Long total = named.queryForObject(
                "SELECT count(DISTINCT name) FROM tp_api_spec WHERE project_id = :pid",
                new MapSqlParameterSource("pid", projectId), Long.class);
        String sql = SELECT_COLUMNS + """
                WHERE id IN (SELECT MAX(id) FROM tp_api_spec WHERE project_id = :pid
                             GROUP BY name)
                ORDER BY updated_at DESC, id DESC
                LIMIT :limit OFFSET :offset
                """;
        List<ApiSpecInfo> rows = query(sql, new MapSqlParameterSource("pid", projectId)
                .addValue("limit", safeSize).addValue("offset", (long) (safePage - 1) * safeSize));
        return new PageResult<>(rows, total == null ? 0 : total);
    }


    private long insert(long projectId, Long documentId, String name, String format,
                        ParsedApiSpec parsed, String sha256, int version,
                        Long prevId, SpecDiff diff, long operatorId, String specText) {
        String sql = """
                INSERT INTO tp_api_spec (project_id, document_id, name, spec_format, spec_title,
                    spec_text, content_sha256, spec_version, endpoints, prev_spec_id, diff_summary,
                    created_by, updated_at)
                VALUES (:projectId, :documentId, :name, :format, :title,
                    :specText, :sha, :version, :endpoints::jsonb, :prevId, :diff::jsonb,
                    :operatorId, now())
                RETURNING id
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("documentId", documentId)
                .addValue("name", name)
                .addValue("format", format)
                .addValue("title", parsed.title())
                .addValue("specText", specText)
                .addValue("sha", sha256)
                .addValue("version", version)
                .addValue("endpoints", toJson(parsed.endpoints()))
                .addValue("prevId", prevId)
                .addValue("diff", diff == null ? null : toJson(diffMap(diff)))
                .addValue("operatorId", operatorId);
        try {
            List<Long> ids = new ArrayList<>();
            named.query(sql, params, (RowCallbackHandler) rs -> ids.add(rs.getLong(1)));
            if (ids.isEmpty()) {
                throw new IllegalStateException("契约版本行写入未返回 id");
            }
            return ids.get(0);
        } catch (DuplicateKeyException e) {
            throw new ServiceException("契约「" + name + "」版本 " + version
                    + " 正在被并发写入（重复提交）——请刷新「接口清单」确认该版本是否已入列表，未入再重新提交",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }


    private List<ApiSpecInfo> query(String sql, MapSqlParameterSource params) {
        List<ApiSpecInfo> out = new ArrayList<>();
        named.query(sql, params, (RowCallbackHandler) rs -> out.add(toInfo(rs)));
        return out;
    }

    private ApiSpecInfo toInfo(ResultSet rs) {
        try {
            Timestamp createdAt = rs.getTimestamp("created_at");
            String diffText = rs.getString("diff_text");
            return new ApiSpecInfo(rs.getLong("id"), rs.getLong("project_id"),
                    rs.getObject("document_id") == null ? null : rs.getLong("document_id"),
                    rs.getString("name"), rs.getString("spec_format"), rs.getString("spec_title"),
                    rs.getString("content_sha256"), rs.getInt("spec_version"),
                    readEndpoints(rs.getString("endpoints_text")),
                    rs.getObject("prev_spec_id") == null ? null : rs.getLong("prev_spec_id"),
                    diffText == null ? null : readDiff(diffText),
                    rs.getObject("created_by") == null ? null : rs.getLong("created_by"),
                    ViewFormats.formatTs(createdAt));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("契约行读取失败", e);
        }
    }

    private List<ApiEndpoint> readEndpoints(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<List<ApiEndpoint>>() { });
        } catch (Exception e) {
            throw new IllegalStateException("契约清单 JSON 反序列化失败", e);
        }
    }

    private SpecDiff readDiff(String json) {
        try {
            Map<String, List<String>> map = objectMapper.readValue(json,
                    new TypeReference<Map<String, List<String>>>() { });
            return new SpecDiff(map.getOrDefault("added", List.of()),
                    map.getOrDefault("changed", List.of()),
                    map.getOrDefault("removed", List.of()));
        } catch (Exception e) {
            throw new IllegalStateException("契约 diff JSON 反序列化失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("契约 JSON 序列化失败", e);
        }
    }

    
    private ParsedApiSpec parseEndpoints(ApiSpecInfo prev) {
        return new ParsedApiSpec(prev.specFormat(), prev.specTitle(), null, prev.endpoints());
    }

    // ---------- diff ----------

    
    static SpecDiff diffOf(ParsedApiSpec prev, ParsedApiSpec current) {
        // Logic moved to SpecDiffOps (single source shared with the mock
        // upgrade planner); this delegate keeps the same-package consumers.
        return com.sixbean.testpike.core.spec.domain.component.SpecDiffOps.diffOf(prev, current);
    }

    private static Map<String, List<String>> diffMap(SpecDiff diff) {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("added", diff.added());
        map.put("changed", diff.changed());
        map.put("removed", diff.removed());
        return map;
    }


    
    static String detectFormat(byte[] content) {
        for (byte b : content) {
            char c = (char) (b & 0xFF);
            if (Character.isWhitespace(c)) {
                continue;
            }
            return c == '{' || c == '[' ? OpenApiSpecParser.FORMAT_JSON
                    : OpenApiSpecParser.FORMAT_YAML;
        }
        throw new ServiceException("契约文件内容为空，请检查上传文件", BizErrorCode.VALIDATION_ERROR);
    }

    private ApiSpecInfo findLatest(long projectId, String name) {
        List<ApiSpecInfo> rows = query(SELECT_COLUMNS + """
                 WHERE project_id = :pid AND name = :name
                 ORDER BY spec_version DESC LIMIT 1""",
                new MapSqlParameterSource("pid", projectId).addValue("name", name));
        return rows.isEmpty() ? null : rows.get(0);
    }
}
