package com.sixbean.testpike.core.knowledge.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.common.domain.component.ViewFormats;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitInput;
import com.sixbean.testpike.core.knowledge.api.dto.KnowledgeHitItem;
import com.sixbean.testpike.core.knowledge.domain.component.KnowledgeJsonColumns;
import com.sixbean.testpike.core.knowledge.domain.service.KnowledgeHitService;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class KnowledgeHitServiceImpl implements KnowledgeHitService {

    
    static final int TASK_TITLE_MAX = 50;
    
    static final int HITS_MAX = 200;

    private final NamedParameterJdbcTemplate named;

    public KnowledgeHitServiceImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public int record(List<KnowledgeHitInput> hits) {
        if (hits == null || hits.isEmpty()) {
            return 0;
        }
        for (KnowledgeHitInput h : hits) {
            validateShape(h);
        }
        int written = 0;
        for (KnowledgeHitInput h : hits) {
            written += named.update("""
                    INSERT INTO tp_knowledge_hit (doc_id, task_id, scene, chunk_ids)
                    SELECT :docId, :taskId, :scene, :chunkIds::jsonb
                    WHERE EXISTS (SELECT 1 FROM tp_knowledge_doc WHERE id = :docId)
                    """, new MapSqlParameterSource()
                    .addValue("docId", h.docId())
                    .addValue("taskId", h.taskId())
                    .addValue("scene", h.scene())
                    .addValue("chunkIds", KnowledgeJsonColumns.writeJsonArray(h.chunkIds())));
        }
        return written;
    }

    @Override
    public List<KnowledgeHitItem> listHits(long docId, int limit) {
        int safeLimit = limit <= 0 ? 20 : Math.min(limit, HITS_MAX);
        return named.query("""
                SELECT h.id, h.task_id,
                       LEFT(t.requirement_text, :titleMax) AS task_title,
                       h.scene, h.chunk_ids::text AS chunk_ids, h.created_at
                FROM tp_knowledge_hit h
                LEFT JOIN tp_ai_task t ON t.id = h.task_id
                WHERE h.doc_id = :docId
                ORDER BY h.id DESC
                LIMIT :limit
                """, new MapSqlParameterSource()
                .addValue("docId", docId)
                .addValue("titleMax", TASK_TITLE_MAX)
                .addValue("limit", safeLimit),
                (rs, rowNum) -> new KnowledgeHitItem(
                        rs.getLong("id"),
                        rs.getObject("task_id", Long.class),
                        rs.getString("task_title"),
                        rs.getString("scene"),
                        KnowledgeJsonColumns.readStringList(rs.getString("chunk_ids")),
                        ViewFormats.formatTs(rs.getTimestamp("created_at"))));
    }

    
    private void validateShape(KnowledgeHitInput h) {
        if (h == null || h.docId() == null || h.docId() <= 0) {
            throw new ServiceException("知识留痕缺少 docId", BizErrorCode.VALIDATION_ERROR);
        }
        if (h.scene() == null || h.scene().isBlank()) {
            throw new ServiceException("知识留痕缺少检索场景（scene）", BizErrorCode.VALIDATION_ERROR);
        }
    }
}
