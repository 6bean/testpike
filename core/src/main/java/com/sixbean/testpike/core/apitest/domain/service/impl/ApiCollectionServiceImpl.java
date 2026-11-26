package com.sixbean.testpike.core.apitest.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.apitest.api.dto.ApiCollectionInfo;
import com.sixbean.testpike.core.apitest.api.dto.CollectionSaveInput;
import com.sixbean.testpike.core.apitest.domain.component.ApiStoredJsonContract;
import com.sixbean.testpike.core.apitest.domain.component.AuthFormContract;
import com.sixbean.testpike.core.apitest.domain.service.ApiCollectionService;
import com.sixbean.testpike.core.apitest.infrastructure.dataobject.ApiCollectionRow;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.program.domain.component.ProgramLensScope;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class ApiCollectionServiceImpl implements ApiCollectionService {

    private static final int MAX_NAME = 100;
    private static final int MAX_TAGS = 500;

    private final NamedParameterJdbcTemplate namedJdbc;
    private final ProgramLensScope programLensScope;

    public ApiCollectionServiceImpl(NamedParameterJdbcTemplate namedJdbc,
                                    ProgramLensScope programLensScope) {
        this.namedJdbc = namedJdbc;
        this.programLensScope = programLensScope;
    }

    @Override
    @Audit(objectType = "apitestCollection", action = "create", after = "#result")
    public ApiCollectionInfo create(Long operatorId, CollectionSaveInput input) {
        if (input == null || input.projectId() == null || input.projectId() <= 0) {
            throw new ServiceException("集合必须挂在项目下，请在顶栏选定项目后，于「接口测试」页左侧集合树新建集合", BizErrorCode.VALIDATION_ERROR);
        }
        String name = requireName(input.name());
        long parentId = input.parentId() == null ? 0L : input.parentId();
        if (parentId > 0) {
            ApiCollectionRow parent = requireCollection(parentId);
            if (parent.getParentId() != 0) {
                throw new ServiceException("集合目录最多两级，不能在子目录下再建子目录；请在「接口测试」集合树把目录建在顶层或一级目录下",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (parent.getProjectId() != input.projectId()) {
                throw new ServiceException("父集合与项目不匹配，请在「接口测试」集合树重选同一顶栏项目下的父集合后提交", BizErrorCode.VALIDATION_ERROR);
            }
        }
        String tags = normalizeTags(input.tags());
        // collection defaults: same normalizer + vocabulary as the request face
        // (ApiStoredJsonContract / AuthFormContract single source — no second dialect).
        String headersJson = ApiStoredJsonContract.normalize(input.headersJson(), "[]", "集合默认请求头", true);
        String authJson = gateCollectionAuth(input.authJson());
        checkNameUnique(input.projectId(), parentId, name, 0L);
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("projectId", input.projectId())
                .addValue("parentId", parentId)
                .addValue("name", name)
                .addValue("tags", tags)
                .addValue("headersJson", headersJson)
                .addValue("authJson", authJson)
                .addValue("operatorId", operatorId);
        Long id = namedJdbc.queryForObject("""
                INSERT INTO tp_apitest_collection (project_id, parent_id, name, tags,
                                                  headers_json, auth_json, created_by)
                VALUES (:projectId, :parentId, :name, :tags,
                        :headersJson::jsonb, :authJson::jsonb, :operatorId) RETURNING id
                """, params, Long.class);
        return new ApiCollectionInfo(id, input.projectId(), parentId, name, tags, 0L,
                headersJson, authJson, List.of());
    }

    @Override
    @Audit(objectType = "apitestCollection", action = "update", objectId = "#collectionId")
    public ApiCollectionInfo rename(Long operatorId, long collectionId, String newName, String tags,
                                    String headersJson, String authJson) {
        ApiCollectionRow current = requireCollection(collectionId);
        String name = newName == null || newName.trim().isEmpty()
                ? current.getName() : requireName(newName);
        String newTags = tags == null ? current.getTags() : normalizeTags(tags);
        // null = keep stored value (patch semantics; the legacy four-argument face
        // delegates here with both nulls so a plain rename never touches defaults).
        String newHeaders = headersJson == null
                ? current.getHeadersJson()
                : ApiStoredJsonContract.normalize(headersJson, "[]", "集合默认请求头", true);
        String newAuth = authJson == null
                ? current.getAuthJson()
                : gateCollectionAuth(authJson);
        if (!name.equals(current.getName())) {
            checkNameUnique(current.getProjectId(), current.getParentId(), name, collectionId);
        }
        namedJdbc.update("""
                UPDATE tp_apitest_collection
                   SET name = :name, tags = :tags,
                       headers_json = :headersJson::jsonb, auth_json = :authJson::jsonb,
                       updated_at = now()
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("name", name)
                .addValue("tags", newTags)
                .addValue("headersJson", newHeaders)
                .addValue("authJson", newAuth)
                .addValue("id", collectionId));
        return new ApiCollectionInfo(collectionId, current.getProjectId(), current.getParentId(),
                name, newTags, countRequests(collectionId), newHeaders, newAuth, List.of());
    }

    /** Shape + vocabulary gate for a collection-level auth block . */
    private String gateCollectionAuth(String raw) {
        String normalized = ApiStoredJsonContract.normalize(raw, "{}", "集合默认认证配置", false);
        String violation = AuthFormContract.violation(
                ApiStoredJsonContract.read(normalized, "集合默认认证配置"));
        if (violation != null) {
            throw new ServiceException(violation, BizErrorCode.VALIDATION_ERROR);
        }
        return normalized;
    }

    @Override
    @Audit(objectType = "apitestCollection", action = "delete", objectId = "#collectionId")
    public void delete(Long operatorId, long collectionId) {
        requireCollection(collectionId);
        Long children = namedJdbc.queryForObject("""
                SELECT count(*) FROM tp_apitest_collection
                 WHERE parent_id = :id AND id <> :id
                """, new MapSqlParameterSource("id", collectionId), Long.class);
        if (children != null && children > 0) {
            throw new ServiceException("该集合下还有子目录，请先删除子目录和请求；入口：「接口测试」集合树逐级删除后再删本目录",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        if (countRequests(collectionId) > 0) {
            throw new ServiceException("该集合下还有 API 请求，请先删除请求；入口：「接口测试」集合树展开该目录逐条删除",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        namedJdbc.update("DELETE FROM tp_apitest_collection WHERE id = :id",
                new MapSqlParameterSource("id", collectionId));
    }

    @Override
    public List<ApiCollectionInfo> list(long projectId, long programId, Long operatorId) {
        // Program lens : resolved by the ProgramLensScope single source.
        // null = no lens (legacy path untouched); empty = honest empty forest (1 = 0);
        // non-empty = lens projects ∩ visibility, still conjunctive with an explicit
        // project anchor (same three-branch posture as the batch and defect domains).
        Set<Long> lensProjectIds = programId > 0
                ? programLensScope.visibleProjectIds(programId, operatorId)
                : null;
        String scope;
        MapSqlParameterSource params = new MapSqlParameterSource();
        if (lensProjectIds == null) {
            scope = " WHERE c.project_id = :projectId";
            params.addValue("projectId", projectId);
        } else if (lensProjectIds.isEmpty()) {
            scope = " WHERE 1 = 0";
        } else {
            scope = " WHERE c.project_id IN (:lensProjectIds)";
            params.addValue("lensProjectIds", lensProjectIds);
            if (projectId > 0L) {
                scope += " AND c.project_id = :projectId";
                params.addValue("projectId", projectId);
            }
        }
        List<ApiCollectionRow> rows = namedJdbc.query("""
                SELECT c.id, c.project_id AS projectId, c.parent_id AS parentId, c.name, c.tags,
                       c.headers_json::text AS headersJson, c.auth_json::text AS authJson,
                       (SELECT count(*) FROM tp_apitest_request r WHERE r.collection_id = c.id) AS requestCount
                  FROM tp_apitest_collection c
                """ + scope + " ORDER BY c.id", params,
                new BeanPropertyRowMapper<>(ApiCollectionRow.class));
        Map<Long, List<ApiCollectionInfo>> childBuckets = new LinkedHashMap<>();
        List<ApiCollectionRow> rootRows = new ArrayList<>();
        for (ApiCollectionRow row : rows) {
            if (row.getParentId() == 0) {
                rootRows.add(row);
            } else {
                childBuckets.computeIfAbsent(row.getParentId(), k -> new ArrayList<>())
                        .add(new ApiCollectionInfo(row.getId(), row.getProjectId(),
                                row.getParentId(), row.getName(), row.getTags(),
                                row.getRequestCount(), row.getHeadersJson(), row.getAuthJson(),
                                List.of()));
            }
        }
        List<ApiCollectionInfo> roots = new ArrayList<>();
        for (ApiCollectionRow row : rootRows) {
            roots.add(new ApiCollectionInfo(row.getId(), row.getProjectId(), row.getParentId(),
                    row.getName(), row.getTags(), row.getRequestCount(),
                    row.getHeadersJson(), row.getAuthJson(),
                    childBuckets.getOrDefault(row.getId(), List.of())));
        }
        return roots;
    }

    @Override
    public ApiCollectionInfo get(long collectionId) {
        ApiCollectionRow row = requireCollection(collectionId);
        return new ApiCollectionInfo(row.getId(), row.getProjectId(), row.getParentId(),
                row.getName(), row.getTags(), row.getRequestCount(),
                row.getHeadersJson(), row.getAuthJson(), List.of());
    }

    /** Direct-holder request count for one collection (no child rollup). */
    private long countRequests(long collectionId) {
        Long count = namedJdbc.queryForObject(
                "SELECT count(*) FROM tp_apitest_request WHERE collection_id = :id",
                new MapSqlParameterSource("id", collectionId), Long.class);
        return count == null ? 0L : count;
    }

    private ApiCollectionRow requireCollection(long id) {
        List<ApiCollectionRow> rows = namedJdbc.query("""
                SELECT c.id, c.project_id AS projectId, c.parent_id AS parentId, c.name, c.tags,
                       c.headers_json::text AS headersJson, c.auth_json::text AS authJson,
                       (SELECT count(*) FROM tp_apitest_request r WHERE r.collection_id = c.id) AS requestCount
                  FROM tp_apitest_collection c WHERE c.id = :id
                """, new MapSqlParameterSource("id", id),
                new BeanPropertyRowMapper<>(ApiCollectionRow.class));
        if (rows.isEmpty()) {
            throw new ServiceException("API 测试集合不存在或已删除，请在「接口测试」页刷新集合树确认后再操作", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    private String requireName(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            throw new ServiceException("集合名称不能为空，请在「接口测试」的集合新建/重命名弹窗补填名称", BizErrorCode.VALIDATION_ERROR);
        }
        String name = raw.trim();
        if (name.length() > MAX_NAME) {
            throw new ServiceException("集合名称最长 " + MAX_NAME + " 字，请在「接口测试」重命名弹窗精简名称后重新提交", BizErrorCode.VALIDATION_ERROR);
        }
        return name;
    }

    private String normalizeTags(String raw) {
        if (raw == null) {
            return "";
        }
        String tags = raw.trim();
        if (tags.length() > MAX_TAGS) {
            throw new ServiceException("集合标签最长 " + MAX_TAGS + " 字，请在「接口测试」集合编辑处精简标签", BizErrorCode.VALIDATION_ERROR);
        }
        return tags;
    }

    private void checkNameUnique(long projectId, long parentId, String name, long excludeId) {
        Long count = namedJdbc.queryForObject("""
                SELECT count(*) FROM tp_apitest_collection
                 WHERE project_id = :projectId AND parent_id = :parentId
                   AND name = :name AND id <> :excludeId
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("parentId", parentId)
                .addValue("name", name)
                .addValue("excludeId", excludeId), Long.class);
        if (count != null && count > 0) {
            throw new ServiceException("同层已存在同名集合「" + name + "」，请在「接口测试」集合树换一个名称后重新提交",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
    }
}
