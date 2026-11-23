package com.sixbean.testpike.aiservice.knowledge.domain.component;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;


@Component
public class KnowledgeChunkMetadataUpdater {

    
    private static final Set<String> UPDATABLE_FIELDS = Set.of(
            KnowledgeMetadataFields.EXCELLENT, KnowledgeMetadataFields.STATUS);

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public KnowledgeChunkMetadataUpdater(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public int updateCaseMetadata(String field, String jsonValue, long caseId) {
        if (!UPDATABLE_FIELDS.contains(field)) {
            throw new IllegalArgumentException("metadata 键不在直改白名单：" + field);
        }
        return jdbcTemplate.update(
                "UPDATE knowledge.tp_knowledge_chunk"
                        + " SET metadata = jsonb_set(metadata, ARRAY[:field], CAST(:value AS jsonb))"
                        + " WHERE metadata->>'case_id' = :caseId",
                new MapSqlParameterSource()
                        .addValue("field", field)
                        .addValue("value", jsonValue)
                        .addValue("caseId", String.valueOf(caseId)));
    }
}
