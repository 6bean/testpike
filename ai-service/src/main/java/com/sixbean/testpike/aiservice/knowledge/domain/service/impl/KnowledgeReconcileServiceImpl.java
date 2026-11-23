package com.sixbean.testpike.aiservice.knowledge.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeReconcileResult;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkDocumentFactory;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeLangResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeReconcileService;
import com.sixbean.testpike.aiservice.llm.api.dto.LlmAuditRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;


@Service
public class KnowledgeReconcileServiceImpl implements KnowledgeReconcileService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeReconcileServiceImpl.class);

    
    static final String AUDIT_OBJECT_TYPE = "knowledge_reconcile";

    private final VectorStore vectorStore;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final CoreGateway coreGateway;
    private final KnowledgeLangResolver langResolver;

    public KnowledgeReconcileServiceImpl(VectorStore vectorStore,
                                         NamedParameterJdbcTemplate jdbcTemplate,
                                         CoreGateway coreGateway,
                                         KnowledgeLangResolver langResolver) {
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.coreGateway = coreGateway;
        this.langResolver = langResolver;
    }

    @Override
    public KnowledgeReconcileResult reconcile() {
        return repair(false);
    }

    @Override
    public KnowledgeReconcileResult rebuildAll() {
        return repair(true);
    }

    
    private KnowledgeReconcileResult repair(boolean fullRebuild) {
        List<Map<String, Object>> ledgerRows = jdbcTemplate.queryForList(
                "SELECT id, project_id, source_type, source_ref, title, content"
                        + " FROM tp_knowledge_doc WHERE status = '" + KnowledgeMetadataFields.ACTIVE + "' ORDER BY id",
                new MapSqlParameterSource());
        Set<Long> vectorDocIds = listVectorDocIds();

        List<Long> missingDocIds = ledgerRows.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .filter(id -> !vectorDocIds.contains(id))
                .collect(Collectors.toList());

        Set<Long> ledgerActiveIds = ledgerRows.stream()
                .map(row -> ((Number) row.get("id")).longValue())
                .collect(Collectors.toSet());
        List<Long> orphanDocIds = vectorDocIds.stream()
                .filter(id -> !ledgerActiveIds.contains(id))
                .collect(Collectors.toList());

        List<Long> targets = fullRebuild
                ? ledgerRows.stream().map(row -> ((Number) row.get("id")).longValue()).toList()
                : missingDocIds;

        for (Long docId : targets) {
            Map<String, Object> row = ledgerRows.stream()
                    .filter(r -> ((Number) r.get("id")).longValue() == docId)
                    .findFirst().orElseThrow();
            repairOne(row);
        }
        for (Long docId : orphanDocIds) {
            vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        }

        KnowledgeReconcileResult result = new KnowledgeReconcileResult(
                targets, orphanDocIds, ledgerRows.size(), countVectorRows());
        if (!result.clean()) {
            audit(fullRebuild, result);
        }
        log.info("G1 知识库{}完成：台账ACTIVE={} missing重嵌入={} orphan删={} 向量行数={}",
                fullRebuild ? "全量重建" : "对账", ledgerRows.size(),
                missingDocIds.size(), orphanDocIds.size(), result.vectorRowsAfter());
        return result;
    }

    
    private void repairOne(Map<String, Object> ledgerRow) {
        long docId = ((Number) ledgerRow.get("id")).longValue();
        long projectId = ((Number) ledgerRow.get("project_id")).longValue();
        String sourceType = String.valueOf(ledgerRow.get("source_type"));
        Object sourceRef = ledgerRow.get("source_ref");
        long caseId = KnowledgeMetadataFields.SOURCE_TYPE_TEST_CASE.equals(sourceType) && sourceRef != null
                ? ((Number) sourceRef).longValue() : 0L;
        Document document = KnowledgeChunkDocumentFactory.create(docId, projectId, 1,
                String.valueOf(ledgerRow.get("title")), String.valueOf(ledgerRow.get("content")),
                resolveProjectLang(projectId), sourceType, caseId, false);
        vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        vectorStore.add(List.of(document));
    }

    private Set<Long> listVectorDocIds() {
        return new HashSet<>(jdbcTemplate.queryForList(
                "SELECT DISTINCT (metadata->>'doc_id')::bigint FROM knowledge.tp_knowledge_chunk",
                new MapSqlParameterSource(), Long.class));
    }

    private int countVectorRows() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM knowledge.tp_knowledge_chunk",
                new MapSqlParameterSource(), Integer.class);
        return count == null ? 0 : count;
    }

    
    private String resolveProjectLang(long projectId) {
        return langResolver.resolve(projectId);
    }

    
    private void audit(boolean fullRebuild, KnowledgeReconcileResult result) {
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("mode", fullRebuild ? "FULL_REBUILD" : "RECONCILE");
        before.put("missing_doc_ids", result.repairedDocIds());
        before.put("orphan_doc_ids", result.orphanDeletedDocIds());
        before.put("ledger_active_rows", result.ledgerActiveRows());
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("repaired", result.repairedDocIds().size());
        after.put("orphan_deleted", result.orphanDeletedDocIds().size());
        after.put("vector_rows_after", result.vectorRowsAfter());
        coreGateway.writeAudit(new LlmAuditRequest(null, 0L, AUDIT_OBJECT_TYPE,
                "knowledge-reconcile", "KB_RECONCILE", before, after));
    }
}
