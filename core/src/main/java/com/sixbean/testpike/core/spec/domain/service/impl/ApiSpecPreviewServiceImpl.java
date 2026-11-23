package com.sixbean.testpike.core.spec.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.ApiRequestInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.api.dto.RequestSaveInput;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.domain.service.ApiRequestService;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecInfo;
import com.sixbean.testpike.core.spec.api.dto.ApiSpecPreviewInfo;
import com.sixbean.testpike.core.spec.api.dto.SpecPreviewApplyResult;
import com.sixbean.testpike.core.document.api.dto.DocumentInfo;
import com.sixbean.testpike.core.document.domain.service.DocumentService;
import com.sixbean.testpike.core.spec.domain.component.ApiSpecDraftItem;
import com.sixbean.testpike.core.spec.domain.component.OpenApiSpecParser;
import com.sixbean.testpike.core.spec.domain.component.ParsedApiSpec;
import com.sixbean.testpike.core.spec.domain.component.SpecDraftGrounding;
import com.sixbean.testpike.core.spec.domain.component.SpecDraftMapper;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecPreviewService;
import com.sixbean.testpike.core.spec.domain.service.ApiSpecService;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class ApiSpecPreviewServiceImpl implements ApiSpecPreviewService {

    
    static final String KEY_DURATION_MS = "apitest.import.duration-ms";
    static final String KEY_BODY_CHECK_PATH = "apitest.import.body-check-path";
    static final String KEY_BODY_CHECK_EXPECT = "apitest.import.body-check-expect";
    static final int DEFAULT_DURATION_MS = 3000;
    static final String DEFAULT_BODY_CHECK_PATH = "$.code";
    static final String DEFAULT_BODY_CHECK_EXPECT = "0";

    private static final String SELECT_COLUMNS = """
            SELECT id, project_id, spec_id, source_document_id, items::text AS items_text,
                   applied_keys::text AS applied_text, status, created_at
            FROM tp_api_spec_preview
            """;

    private final NamedParameterJdbcTemplate named;
    private final ApiSpecService apiSpecService;
    private final ApiCollectionService apiCollectionService;
    private final ApiRequestService apiRequestService;
    private final SystemConfigReader configReader;
    private final DocumentService documentService;
    private final SpecDraftMapper mapper = new SpecDraftMapper();
    private final SpecDraftGrounding grounding = new SpecDraftGrounding();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ApiSpecPreviewServiceImpl(NamedParameterJdbcTemplate named,
                                     ApiSpecService apiSpecService,
                                     ApiCollectionService apiCollectionService,
                                     ApiRequestService apiRequestService,
                                     SystemConfigReader configReader,
                                     DocumentService documentService) {
        this.named = named;
        this.apiSpecService = apiSpecService;
        this.apiCollectionService = apiCollectionService;
        this.apiRequestService = apiRequestService;
        this.configReader = configReader;
        this.documentService = documentService;
    }

    @Override
    @Audit(objectType = "api_spec_preview", action = "generate", objectId = "#result.id",
            after = "#result.status")
    @Transactional
    public ApiSpecPreviewInfo generate(Long operatorId, long specId) {
        ApiSpecInfo spec = apiSpecService.get(specId);
        ParsedApiSpec replay = new ParsedApiSpec(spec.specFormat(), spec.specTitle(),
                null, spec.endpoints());
        List<ApiSpecDraftItem> items = mapper.fromParsedSpec(replay,
                configReader.getInt(KEY_DURATION_MS, DEFAULT_DURATION_MS),
                configReader.getString(KEY_BODY_CHECK_PATH, DEFAULT_BODY_CHECK_PATH),
                configReader.getString(KEY_BODY_CHECK_EXPECT, DEFAULT_BODY_CHECK_EXPECT));
        Long id = named.queryForObject("""
                INSERT INTO tp_api_spec_preview (project_id, spec_id, items, status, created_by)
                VALUES (:projectId, :specId, :items::jsonb, 'pending', :operatorId)
                ON CONFLICT (spec_id, project_id) DO UPDATE
                    SET items = :items::jsonb, updated_at = now()
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", spec.projectId())
                .addValue("specId", specId)
                .addValue("items", toJson(items))
                .addValue("operatorId", operatorId), Long.class);
        return get(id);
    }

    @Override
    public ApiSpecPreviewInfo get(long previewId) {
        List<ApiSpecPreviewInfo> rows = new ArrayList<>();
        named.query(SELECT_COLUMNS + " WHERE id = :id",
                new MapSqlParameterSource("id", previewId),
                (RowCallbackHandler) rs -> rows.add(toInfo(rs)));
        if (rows.isEmpty()) {
            throw new ServiceException("导入预览不存在: " + previewId, BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    public ApiSpecPreviewInfo latestByDocument(long documentId) {
        List<ApiSpecPreviewInfo> rows = new ArrayList<>();
        named.query(SELECT_COLUMNS + " WHERE source_document_id = :documentId AND spec_id IS NULL",
                new MapSqlParameterSource("documentId", documentId),
                (RowCallbackHandler) rs -> rows.add(toInfo(rs)));
        if (rows.isEmpty()) {
            throw new ServiceException("该文档的接口清单抽取还没有结果（抽取任务可能仍在进行或尚未发起），"
                    + "请稍后刷新或重新发起抽取", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    @Audit(objectType = "api_spec_preview", action = "generate_from_drafts", objectId = "#result.id",
            after = "#result.status")
    @Transactional
    public ApiSpecPreviewInfo generateFromDrafts(Long operatorId, long documentId,
                                                 List<ApiSpecDraftItem> rawItems) {
        DocumentInfo doc = documentService.get(documentId);
        List<String> corpus = new ArrayList<>();
        for (Map<String, Object> chunk : documentService.chunks(documentId)) {
            corpus.add(String.valueOf(chunk.get("content")));
        }
        List<ApiSpecDraftItem> items = new ArrayList<>();
        for (ApiSpecDraftItem judged : grounding.apply(rawItems, corpus)) {
            items.add(withEndpointKey(judged));
        }
        Long id = named.queryForObject("""
                INSERT INTO tp_api_spec_preview (project_id, spec_id, source_document_id,
                                                 items, status, created_by)
                VALUES (:projectId, NULL, :documentId, :items::jsonb, 'pending', :operatorId)
                ON CONFLICT (source_document_id, project_id) WHERE spec_id IS NULL
                DO UPDATE SET items = :items::jsonb, updated_at = now()
                RETURNING id
                """, new MapSqlParameterSource()
                .addValue("projectId", doc.projectId())
                .addValue("documentId", documentId)
                .addValue("items", toJson(items))
                .addValue("operatorId", operatorId), Long.class);
        return get(id);
    }

    
    private ApiSpecDraftItem withEndpointKey(ApiSpecDraftItem item) {
        if (item.endpointKey() != null && !item.endpointKey().isBlank()) {
            return item;
        }
        String method = item.method() == null ? "" : item.method().trim().toUpperCase(Locale.ROOT);
        String key = method + " " + OpenApiSpecParser.normalizePath(item.path());
        return new ApiSpecDraftItem(key, item.method(), item.path(), item.title(),
                item.description(), item.tags(), item.paramsSkeletonJson(),
                item.requestContentType(), item.bodySkeleton(), item.assertionsJson(),
                item.negativeAssertionsJson(), item.negativeSelected(), item.securityRefs(),
                item.authHintJson(), item.channel(), item.confidence(), item.sourceAnchor());
    }

    @Override
    @Audit(objectType = "api_spec_preview", action = "apply", objectId = "#previewId")
    @Transactional
    public SpecPreviewApplyResult apply(Long operatorId, long previewId, List<String> selectedKeys) {
        ApiSpecPreviewInfo preview = get(previewId);
        Map<String, ApiSpecDraftItem> byKey = new LinkedHashMap<>();
        for (ApiSpecDraftItem item : preview.items()) {
            byKey.put(item.endpointKey(), item);
        }
        Set<String> applied = readStringSet(appliedText(previewId));
        List<String> selection = selectedKeys == null ? List.of() : selectedKeys;
        Set<String> dedup = new LinkedHashSet<>();
        for (String key : selection) {
            if (!byKey.containsKey(key)) {
                throw new ServiceException("勾选的接口「" + key + "」不在本次预览清单内（预览可能已过期或被新预览替换）——"
                        + "请在「接口清单」重新打开预览，仅勾选当前清单内的接口", BizErrorCode.VALIDATION_ERROR);
            }
            dedup.add(key);
        }

        Map<String, Long> collectionCache = collectionIndex(preview.projectId());
        List<Long> createdIds = new ArrayList<>();
        int skipped = 0;
        Set<String> newApplied = new LinkedHashSet<>(applied);
        for (String key : dedup) {
            if (applied.contains(key)) {
                skipped++;
                continue;
            }
            ApiSpecDraftItem item = byKey.get(key);
            long collectionId = resolveCollection(collectionCache, preview.projectId(),
                    item, rootFallbackName(preview), operatorId);
            ApiRequestInfo created = apiRequestService.create(operatorId, toSaveInput(
                    collectionId, item));
            createdIds.add(created.id());
            newApplied.add(key);
        }

        boolean allApplied = newApplied.size() >= byKey.size() && !byKey.isEmpty();
        named.update("""
                UPDATE tp_api_spec_preview
                   SET applied_keys = :applied::jsonb,
                       status = :status, updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("applied", toJson(newApplied))
                .addValue("status", allApplied ? "applied" : "pending")
                .addValue("id", previewId));
        return new SpecPreviewApplyResult(createdIds.size(), skipped, createdIds);
    }


    
    private long resolveCollection(Map<String, Long> index, long projectId,
                                   ApiSpecDraftItem item, String rootFallback,
                                   Long operatorId) {
        String root = item.tags() == null || item.tags().isEmpty()
                ? rootFallback : item.tags().get(0).trim();
        if (root.isEmpty()) {
            root = rootFallback;
        }
        long rootId = index.computeIfAbsent(root,
                name -> createCollection(projectId, 0L, name, operatorId));
        if (item.tags() == null || item.tags().size() < 2) {
            return rootId;
        }
        String child = item.tags().get(1).trim();
        if (child.isEmpty()) {
            return rootId;
        }
        String childPath = root + "/" + child;
        return index.computeIfAbsent(childPath,
                name -> createCollection(projectId, rootId, child, operatorId));
    }

    private long createCollection(long projectId, long parentId, String name, Long operatorId) {
        ApiCollectionInfo created = apiCollectionService.create(operatorId,
                new CollectionSaveInput(projectId, parentId, name));
        return created.id();
    }

    
    private Map<String, Long> collectionIndex(long projectId) {
        Map<String, Long> index = new LinkedHashMap<>();
        for (ApiCollectionInfo root : apiCollectionService.list(projectId)) {
            index.put(root.name(), root.id());
            for (ApiCollectionInfo child : root.children()) {
                index.put(root.name() + "/" + child.name(), child.id());
            }
        }
        return index;
    }

    
    private String rootFallbackName(ApiSpecPreviewInfo preview) {
        String title;
        if (preview.specId() != null) {
            title = apiSpecService.get(preview.specId()).specTitle();
        } else if (preview.sourceDocumentId() != null) {
            title = documentService.get(preview.sourceDocumentId()).name();
        } else {
            title = null;
        }
        title = title == null ? "" : title.trim();
        return title.isEmpty() ? "接口导入" : title;
    }


    private RequestSaveInput toSaveInput(long collectionId, ApiSpecDraftItem item) {
        String name = item.title() == null || item.title().isBlank()
                ? item.method() + " " + item.path() : item.title();
        String headers = headerSkeletonFromParams(item.paramsSkeletonJson());
        return new RequestSaveInput(collectionId, name, item.method(), item.path(),
                headers, item.bodySkeleton(), item.authHintJson(), item.assertionsJson());
    }

    
    private String headerSkeletonFromParams(String paramsSkeletonJson) {
        List<Map<String, String>> out = new ArrayList<>();
        try {
            List<Map<String, Object>> params = objectMapper.readValue(paramsSkeletonJson,
                    new TypeReference<List<Map<String, Object>>>() { });
            for (Map<String, Object> p : params) {
                if ("header".equalsIgnoreCase(String.valueOf(p.get("location")))) {
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("name", String.valueOf(p.getOrDefault("name", "")));
                    row.put("value", "");
                    out.add(row);
                }
            }
        } catch (Exception e) {
            throw new ServiceException("参数骨架解析失败，无法生成请求", BizErrorCode.VALIDATION_ERROR);
        }
        return toJson(out);
    }


    private String appliedText(long previewId) {
        List<String> out = new ArrayList<>();
        named.query("SELECT applied_keys::text FROM tp_api_spec_preview WHERE id = :id",
                new MapSqlParameterSource("id", previewId),
                (RowCallbackHandler) rs -> out.add(rs.getString(1)));
        return out.isEmpty() ? "[]" : out.get(0);
    }

    private Set<String> readStringSet(String json) {
        try {
            return new LinkedHashSet<>(objectMapper.readValue(json,
                    new TypeReference<List<String>>() { }));
        } catch (Exception e) {
            throw new IllegalStateException("预览已写键清单读取失败", e);
        }
    }

    private ApiSpecPreviewInfo toInfo(java.sql.ResultSet rs) {
        try {
            Timestamp createdAt = rs.getTimestamp("created_at");
            return new ApiSpecPreviewInfo(rs.getLong("id"), rs.getLong("project_id"),
                    (Long) rs.getObject("spec_id"),
                    (Long) rs.getObject("source_document_id"), rs.getString("status"),
                    objectMapper.readValue(rs.getString("items_text"),
                            new TypeReference<List<ApiSpecDraftItem>>() { }),
                    com.sixbean.testpike.core.common.domain.component.ViewFormats
                            .formatTs(createdAt));
        } catch (Exception e) {
            throw new IllegalStateException("预览行读取失败", e);
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("预览 JSON 序列化失败", e);
        }
    }
}
