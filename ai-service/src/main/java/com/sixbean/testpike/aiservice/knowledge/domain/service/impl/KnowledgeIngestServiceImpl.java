package com.sixbean.testpike.aiservice.knowledge.domain.service.impl;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkDocumentFactory;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeChunkMetadataUpdater;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeLangResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.component.ProjectKbGate;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeIngestService;
import com.sixbean.testpike.aiservice.llm.domain.component.EmbeddingBatchSizeResolver;
import com.sixbean.testpike.common.mq.AiKbIngestMessage;
import com.sixbean.testpike.common.mq.CaseIngestEventMessage;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.common.util.ListPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class KnowledgeIngestServiceImpl implements KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestServiceImpl.class);

    
    static final String SOURCE_TYPE_DOC = KnowledgeMetadataFields.SOURCE_TYPE_TEST_SPEC;

    /**
 * Message category -> knowledge ledger source type ( + BA batch
 * decision 9). DDL_DICT passes through unchanged (ledger/vector
 * source_type=DDL_DICT, consumed by the DdlDictBridge prompt path);
 * REQUIREMENT_DOC (the requirement source doc) lands on TEST_SPEC -- the
 * requirement doc and the test spec share one downstream retrieval
 * surface, so the ledger value set is deliberately NOT extended and no
 * second mapping component may exist (single source: this method).
 * Everything else (TEST_SPEC / null = legacy messages) falls back to
 * TEST_SPEC, unknown categories additionally warn by name -- zero
 * semantic drift.
 */
    static String resolveSourceType(String category) {
        String normalized = category == null ? "" : category.trim();
        if (KnowledgeMetadataFields.SOURCE_TYPE_DDL_DICT.equalsIgnoreCase(normalized)) {
            return KnowledgeMetadataFields.SOURCE_TYPE_DDL_DICT;
        }
        if ("REQUIREMENT_DOC".equalsIgnoreCase(normalized)) {
            return SOURCE_TYPE_DOC;
        }
        if (category != null && !category.isBlank()
                && !KnowledgeMetadataFields.SOURCE_TYPE_TEST_SPEC.equalsIgnoreCase(category)) {
            log.warn("G1 ingest unknown document category category={} (falls back to TEST_SPEC, source outside the core upload gate)", category);
        }
        return SOURCE_TYPE_DOC;
    }

    
    static final String SOURCE_TYPE_CASE = KnowledgeMetadataFields.SOURCE_TYPE_TEST_CASE;

    
    static final String SOURCE_TYPE_REQUIREMENT = KnowledgeMetadataFields.SOURCE_TYPE_REQUIREMENT;

    
    private static final int CASE_STATUS_APPROVED = 3;

    private final CoreGateway coreGateway;
    private final VectorStore vectorStore;
    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final EmbeddingBatchSizeResolver batchSizeResolver;
    private final KnowledgeLangResolver langResolver;
    private final KnowledgeChunkMetadataUpdater metadataUpdater;
    private final ProjectKbGate kbGate;

    public KnowledgeIngestServiceImpl(CoreGateway coreGateway, VectorStore vectorStore,
                                      NamedParameterJdbcTemplate jdbcTemplate,
                                      EmbeddingBatchSizeResolver batchSizeResolver,
                                      KnowledgeLangResolver langResolver,
                                      KnowledgeChunkMetadataUpdater metadataUpdater,
                                      ProjectKbGate kbGate) {
        this.coreGateway = coreGateway;
        this.vectorStore = vectorStore;
        this.jdbcTemplate = jdbcTemplate;
        this.batchSizeResolver = batchSizeResolver;
        this.langResolver = langResolver;
        this.metadataUpdater = metadataUpdater;
        this.kbGate = kbGate;
    }

    @Override
    @Transactional
    public void ingest(AiKbIngestMessage message) {
        List<Map<String, Object>> chunks = coreGateway.getDocumentChunks(message.documentId());
        if (chunks == null || chunks.isEmpty()) {
            log.info("G1 ingest skipped: document has no chunks documentId={} (not a terminal state, awaiting redelivery)", message.documentId());
            return;
        }
        String docTitle = resolveDocTitle(message.documentId());
        String lang = resolveProjectLang(message.projectId());

        List<String> maskedContents = new ArrayList<>(chunks.size());
        for (Map<String, Object> chunk : chunks) {
            maskedContents.add(DesensitizeUtils.maskText(
                    chunk.get("content") == null ? "" : String.valueOf(chunk.get("content"))));
        }
        String mergedContent = String.join("\n", maskedContents);
        String sourceType = resolveSourceType(message.category());
        long docId = upsertLedger(sourceType, message.projectId(), message.documentId(),
                docTitle, mergedContent);

        vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);

        List<Document> documents = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            documents.add(buildDocument(docId, message.projectId(), chunks.get(i),
                    maskedContents.get(i), docTitle, lang, i, sourceType));
        }
        addInBatches(documents);
        log.info("G1 document ingest completed documentId={} docId={} chunks={} lang={} sourceType={}",
                message.documentId(), docId, documents.size(), lang, sourceType);
    }

    
    private String resolveDocTitle(long documentId) {
        try {
            Object name = coreGateway.getDocument(documentId).get("name");
            return name == null || String.valueOf(name).isBlank()
                    ? "文档" + documentId : String.valueOf(name);
        } catch (Exception e) {
            log.warn("G1 ingest document-name lookup failed documentId={} err={}", documentId, e.getMessage());
            return "文档" + documentId;
        }
    }

    @Override
    @Transactional
    public void ingestCase(CaseIngestEventMessage message) {
        Map<String, Object> detail = coreGateway.getCase(message.caseId());
        if (detail == null || detail.isEmpty()) {
            log.info("G1 case ingest skipped: detail unreachable caseId={} (awaiting redelivery)", message.caseId());
            return;
        }
        String title = resolveCaseTitle(detail, message.caseId());
        String lang = resolveProjectLang(message.projectId());
        String assembled = DesensitizeUtils.maskText(assembleCaseText(detail));

        long docId = upsertLedger(SOURCE_TYPE_CASE, message.projectId(), message.caseId(),
                title, assembled);

        vectorStore.delete(KnowledgeMetadataFields.CASE_ID + " == " + message.caseId());
        addInBatches(List.of(buildChunkDocument(docId, message.projectId(), 1, title,
                assembled, lang, SOURCE_TYPE_CASE, message.caseId(), resolveExcellent(detail))));
        log.info("G1 case ingest completed caseId={} version={} docId={} lang={}",
                message.caseId(), message.caseVersion(), docId, lang);
    }

    @Override
    @Transactional
    public void ingestRequirement(CaseIngestEventMessage message) {
        CoreGateway.RequirementBrief brief = coreGateway.getRequirement(message.requirementId());
        if (brief == null) {
            log.info("Requirement ingest skipped: detail unreachable requirementId={} (awaiting redelivery / reconciliation convergence)", message.requirementId());
            return;
        }
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "标题", brief.title());
        appendLine(sb, "验收标准", brief.acceptanceCriteria());
        String assembled = DesensitizeUtils.maskText(sb.toString());
        if (assembled.isBlank()) {
            log.info("Requirement ingest skipped: title and acceptance criteria both empty requirementId={} (no content to ingest)", message.requirementId());
            return;
        }
        String title = brief.title() == null || brief.title().isBlank()
                ? "需求" + message.requirementId() : brief.title();
        String lang = resolveProjectLang(message.projectId());
        long docId = upsertLedger(SOURCE_TYPE_REQUIREMENT, message.projectId(),
                message.requirementId(), title, assembled);
        vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        addInBatches(List.of(buildChunkDocument(docId, message.projectId(), 1, title,
                assembled, lang, SOURCE_TYPE_REQUIREMENT, 0L, false)));
        log.info("Requirement ingest completed requirementId={} version={} docId={} lang={}",
                message.requirementId(), message.requirementVersion(), docId, lang);
    }

    @Override
    @Transactional
    public void retireRequirement(long requirementId) {
        List<Long> activeDocIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND source_ref = :requirementId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_REQUIREMENT)
                        .addValue("requirementId", requirementId),
                Long.class);
        if (activeDocIds.isEmpty()) {
            log.info("Requirement offline no-op requirementId={} (never ingested / already offline, naturally idempotent)", requirementId);
            return;
        }
        for (Long docId : activeDocIds) {
            vectorStore.delete(KnowledgeMetadataFields.DOC_ID + " == " + docId);
        }
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE source_type = :sourceType"
                        + " AND source_ref = :requirementId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_REQUIREMENT)
                        .addValue("requirementId", requirementId));
        log.info("Requirement offline completed requirementId={} ledger set INACTIVE={} (vectors purged; exclusion from retrieval closes the self-contamination loop)",
                requirementId, deactivated);
    }

    
    @Override
    @Transactional
    public void applyExcellentFlag(CaseIngestEventMessage message) {
        Map<String, Object> detail = coreGateway.getCase(message.caseId());
        if (detail == null || detail.isEmpty()) {
            log.info("D3 excellent linkage skipped: detail unreachable caseId={} (awaiting redelivery / reconciliation convergence)", message.caseId());
            return;
        }
        boolean excellent = resolveExcellent(detail);
        List<Long> activeDocIds = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND source_ref = :caseId AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource().addValue("sourceType", SOURCE_TYPE_CASE)
                        .addValue("caseId", message.caseId()),
                Long.class);
        if (!activeDocIds.isEmpty()) {
            int updated = metadataUpdater.updateCaseMetadata(
                    KnowledgeMetadataFields.EXCELLENT, String.valueOf(excellent), message.caseId());
            log.info("D3 excellent linkage updates metadata in place caseId={} excellent={} vector-rows={} (no re-vectorization)",
                    message.caseId(), excellent, updated);
            return;
        }
        Object status = detail.get("status");
        if (status instanceof Number n && n.intValue() == CASE_STATUS_APPROVED) {
            if (!kbGate.isAutoIngestEnabled(message.projectId())) {
                log.info("D3 excellent linkage skips the fallback ingest: project knowledge-base switch off caseId={} projectId={} (no ACTIVE rows to gate)",
                        message.caseId(), message.projectId());
                return;
            }
            log.info("D3 excellent linkage: no ACTIVE ledger row but the case is passed caseId={} -> re-ingest with real values", message.caseId());
            ingestCase(message);
            return;
        }
        log.info("D3 excellent linkage skipped: case not ingested and not passed caseId={} status={} (no linkage target)",
                message.caseId(), status);
    }

    
    private static boolean resolveExcellent(Map<String, Object> detail) {
        Object excellent = detail.get("excellent");
        return excellent instanceof Number n ? n.intValue() == 1 : Boolean.parseBoolean(String.valueOf(excellent));
    }

    
    private String resolveCaseTitle(Map<String, Object> detail, long caseId) {
        Object title = detail.get("title");
        if (title != null && !String.valueOf(title).isBlank()) {
            return String.valueOf(title);
        }
        Object caseNo = detail.get("caseNo");
        return caseNo == null || String.valueOf(caseNo).isBlank()
                ? "用例" + caseId : String.valueOf(caseNo);
    }

    
    private String assembleCaseText(Map<String, Object> detail) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, "标题", detail.get("title"));
        appendLine(sb, "前置条件", detail.get("precondition"));
        appendLine(sb, "预期结果", detail.get("expectedResult"));
        if (detail.get("steps") instanceof List<?> steps) {
            int fallbackNo = 1;
            for (Object step : steps) {
                if (step instanceof Map<?, ?> map) {
                    Object no = map.get("stepNo") == null ? fallbackNo : map.get("stepNo");
                    appendLine(sb, "步骤" + no + (Integer.valueOf(1).equals(map.get("isAssert"))
                            || "1".equals(String.valueOf(map.get("isAssert"))) ? "（断言）" : ""),
                            map.get("description"));
                }
                fallbackNo++;
            }
        }
        try {
            List<Map<String, Object>> tags = coreGateway.getCaseTags(
                    ((Number) detail.get("id")).longValue());
            if (tags != null && !tags.isEmpty()) {
                StringBuilder names = new StringBuilder();
                for (Map<String, Object> tag : tags) {
                    if (names.length() > 0) {
                        names.append('、');
                    }
                    names.append(tag.get("name"));
                }
                appendLine(sb, "标签", names.toString());
            }
        } catch (Exception e) {
            log.warn("G1 case label lookup failed, degrading to empty labels without blocking caseId={} err={}",
                    detail.get("id"), e.getMessage());
        }
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String label, Object value) {
        if (value != null && !String.valueOf(value).isBlank()) {
            sb.append(label).append("：").append(value).append('\n');
        }
    }

    
    private String resolveProjectLang(long projectId) {
        return langResolver.resolve(projectId);
    }

    
    private long upsertLedger(String sourceType, long projectId, long sourceRef,
                              String title, String content) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceType", sourceType)
                .addValue("projectId", projectId)
                .addValue("sourceRef", sourceRef)
                .addValue("title", title)
                .addValue("content", content);
        List<Long> existing = jdbcTemplate.queryForList(
                "SELECT id FROM tp_knowledge_doc WHERE source_type = :sourceType"
                        + " AND project_id = :projectId AND source_ref = :sourceRef",
                params, Long.class);
        if (!existing.isEmpty()) {
            // Re-ingest of the same source is a new ledger version (V339): the
            // counter rides the row so citations can name the version that
            // backed an answer. Only content rewrites bump it — status-only
            // updates elsewhere keep the revision untouched.
            jdbcTemplate.update(
                    "UPDATE tp_knowledge_doc SET title = :title, content = :content,"
                            + " status = 'ACTIVE', revision = revision + 1,"
                            + " updated_at = now() WHERE id = :id",
                    params.addValue("id", existing.get(0)));
            return existing.get(0);
        }
        Long inserted = jdbcTemplate.queryForObject(
                "INSERT INTO tp_knowledge_doc (project_id, pack_id, source_type, source_ref,"
                        + " title, content, status)"
                        + " VALUES (:projectId, 0, :sourceType, :sourceRef, :title, :content, 'ACTIVE')"
                        + " RETURNING id",
                params, Long.class);
        if (inserted == null) {
            throw new IllegalStateException("台账插入无返回 id sourceRef=" + sourceRef);
        }
        return inserted;
    }

    private Document buildDocument(long docId, long projectId, Map<String, Object> rawChunk,
                                   String maskedContent, String docTitle, String lang, int index,
                                   String sourceType) {
        int seq = rawChunk.get("seq") == null ? index + 1 : (int) Long.parseLong(
                String.valueOf(rawChunk.get("seq")));
        Object chapter = rawChunk.get("chapter");
        return buildChunkDocument(docId, projectId, seq,
                chapter == null || String.valueOf(chapter).isBlank() ? docTitle : String.valueOf(chapter),
                maskedContent, lang, sourceType, 0L, false);
    }

    
    private Document buildChunkDocument(long docId, long projectId, int seq, String title,
                                        String maskedContent, String lang,
                                        String sourceType, long caseId, boolean excellent) {
        return KnowledgeChunkDocumentFactory.create(docId, projectId, seq, title,
                maskedContent, lang, sourceType, caseId, excellent);
    }

    @Override
    public void cleanupCase(long caseId) {
        vectorStore.delete(KnowledgeMetadataFields.CASE_ID + " == " + caseId);
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE source_type = '" + SOURCE_TYPE_CASE + "'"
                        + " AND source_ref = :caseId AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource("caseId", caseId));
        log.info("G1 case cleanup completed caseId={} ledger set INACTIVE={} (no ledger row = never ingested / duplicate cleanup, naturally idempotent)",
                caseId, deactivated);
    }

    @Override
    public void cleanupProject(long projectId) {
        vectorStore.delete(KnowledgeMetadataFields.PROJECT_ID + " == " + projectId);
        int deactivated = jdbcTemplate.update(
                "UPDATE tp_knowledge_doc SET status = '" + KnowledgeMetadataFields.INACTIVE + "',"
                        + " updated_at = now() WHERE project_id = :projectId"
                        + " AND status = '" + KnowledgeMetadataFields.ACTIVE + "'",
                new MapSqlParameterSource("projectId", projectId));
        log.info("G1 project-level cleanup completed projectId={} ledger set INACTIVE={}", projectId, deactivated);
    }

    /** Batched embedding insert (K8 batch-size hot-tunable, 14 §3.5 default 128 docs/request; split single-sourced in ListPartition). */
    private void addInBatches(List<Document> documents) {
        int batch = batchSizeResolver.resolve();
        for (List<Document> chunk : ListPartition.of(documents, batch)) {
            vectorStore.add(chunk);
        }
    }
}
