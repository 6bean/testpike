package com.sixbean.testpike.core.knowledge.domain.service.impl;

import com.sixbean.testpike.common.exception.ValidationException;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeChunkItem;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocDetail;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeDocItem;
import com.sixbean.testpike.core.knowledge.domain.component.KnowledgeJsonColumns;
import com.sixbean.testpike.core.knowledge.domain.service.KnowledgeDocService;
import com.sixbean.testpike.core.knowledge.infrastructure.dataobject.KnowledgeDocRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Set;


@Service
public class KnowledgeDocServiceImpl implements KnowledgeDocService {

    
    static final Set<String> SOURCE_TYPES = Set.of(
            "TEST_CASE", "FEEDBACK", "STYLE", "TEST_SPEC", "PACK_KNOWLEDGE", "TERM", "DDL_DICT", "MCP_SYNC");
    
    static final Set<String> STATUSES = Set.of("ACTIVE", "INACTIVE");

    private static final String SELECT_DOC = """
            SELECT id, project_id, pack_id, source_type, source_ref, title, content,
                   status, revision, created_at, updated_at
            FROM tp_knowledge_doc
            """;

    private final NamedParameterJdbcTemplate named;

    public KnowledgeDocServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public PageResult<KnowledgeDocItem> list(Long projectId, String sourceType, String status,
                                             int page, int size) {
        return list(projectId, sourceType, status, page, size, null, null);
    }

    @Override
    public PageResult<KnowledgeDocItem> list(Long projectId, String sourceType, String status,
                                             int page, int size, String sortBy, String sortDir) {
        if (sourceType != null && !SOURCE_TYPES.contains(sourceType)) {
            throw new ValidationException("来源类型仅支持 " + String.join("/", SOURCE_TYPES)
                    + "，当前请求：" + sourceType);
        }
        if (status != null && !STATUSES.contains(status)) {
            throw new ValidationException("状态仅支持 ACTIVE/INACTIVE，当前请求：" + status);
        }
        int safeSize = PageQueryPolicy.enforceSize(size);
        long offset = (long) Math.max(page - 1, 0) * safeSize;

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE 1=1");
        if (projectId != null) {
            where.append(" AND project_id = :projectId");
            params.addValue("projectId", projectId);
        }
        if (sourceType != null) {
            where.append(" AND source_type = :sourceType");
            params.addValue("sourceType", sourceType);
        }
        if (status != null) {
            where.append(" AND status = :status");
            params.addValue("status", status);
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_knowledge_doc" + where,
                params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<KnowledgeDocItem> list = named.query(
                SELECT_DOC + where + orderClause(sortBy, sortDir) + " LIMIT :size OFFSET :offset",
                params, new BeanPropertyRowMapper<>(KnowledgeDocRow.class))
                .stream().map(KnowledgeDocRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    /**
 * Sort whitelist for the knowledge ledger . Values are
 * physical columns of tp_knowledge_doc; the map output is the only thing
 * reaching the ORDER BY clause, enforced fail-closed by the shared
 * sort-column gate. content/created_at stay out: the page renders an
 * 80-char snippet (sorting long text is meaningless) and no created column,
 * so exposing them would widen the contract without a consumer.
 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id",
            "title", "title",
            "source_type", "source_type",
            "project_id", "project_id",
            "status", "status",
            "updated_at", "updated_at");

    /**
     * ORDER BY builder for the ledger window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column with
     * the id DESC tie-break pinned (deterministic paging under equal values);
     * an off-list key is refused fail-closed before any database read.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "知识库");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    @Override
    public KnowledgeDocDetail detail(long id) {
        List<KnowledgeDocRow> rows = named.query(SELECT_DOC + " WHERE id = :id",
                new MapSqlParameterSource("id", id), new BeanPropertyRowMapper<>(KnowledgeDocRow.class));
        if (rows.isEmpty()) {
            throw new ValidationException("知识文档不存在（id=" + id + "），请刷新列表重试");
        }
        KnowledgeDocRow row = rows.get(0);
        List<KnowledgeChunkItem> chunks = named.query("""
                SELECT c.id::text AS chunk_id,
                       (c.metadata->>'chunk_index')::int AS chunk_index,
                       c.content, c.metadata::text AS metadata
                FROM knowledge.tp_knowledge_chunk c
                WHERE (c.metadata->>'doc_id')::bigint = :docId
                ORDER BY (c.metadata->>'chunk_index')::int ASC
                """, new MapSqlParameterSource("docId", id),
                (rs, rowNum) -> new KnowledgeChunkItem(
                        rs.getString("chunk_id"),
                        rs.getInt("chunk_index"),
                        rs.getString("content"),
                        KnowledgeJsonColumns.readObjectMap(rs.getString("metadata"))));
        Long hits = named.queryForObject(
                "SELECT count(*) FROM tp_knowledge_hit WHERE doc_id = :docId",
                new MapSqlParameterSource("docId", id), Long.class);
        KnowledgeDocItem info = row.toInfo();
        return new KnowledgeDocDetail(info.id(), info.projectId(), info.packId(),
                info.sourceType(), info.sourceRef(), info.title(), info.content(),
                info.status(), info.createdAt(), info.updatedAt(),
                hits == null ? 0 : hits.intValue(), chunks);
    }
}
