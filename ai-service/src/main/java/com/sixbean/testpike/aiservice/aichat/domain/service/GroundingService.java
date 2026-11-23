package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingHit;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingKernel;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchScene;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.common.defect.DefectLabels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Grounding assembly for one dialog turn (AO batch , service-wiring
 * segment): pulls help chapters (over the core internal endpoint via
 * {@link CoreGateway}) and knowledge chunks (same-process G2 search, scene
 * BUSINESS, asking project's segment — H1 isolation never widened by the chat)
 * into one candidate pool, then hands it to {@link GroundingKernel} for
 * ranking, dedup and the honest zero-hit verdict.
 * <p>Failure posture: every source is fail-soft — a core or vector-store
 * outage drops that source's candidates, never the turn. If nothing survives,
 * the outcome is the zero-hit shape and the orchestrator must tell the model
 * it has no grounded material (the answer then says so plainly instead of
 * improvising platform facts).
 * <p>Retrieval-side hit journaling stays inside KnowledgeSearchService
 * ({@code KnowledgeHitRecorder}) — this class records nothing itself, the
 * attribution ledger has exactly one writer.
 */
@Service
public class GroundingService {

    /** Citation budget key (K8, seeded default below; hot-updatable). */
    static final String KEY_MAX_CITATIONS = "ai.chat.grounding.max-citations";
    /** Excerpt window budget key. */
    static final String KEY_EXCERPT_CHARS = "ai.chat.grounding.excerpt-chars";
    /** Relevance floor key. */
    static final String KEY_MIN_SCORE = "ai.chat.grounding.min-score";
    /** Bar between high and weak material relevance . */
    static final String KEY_STRONG_SCORE = "ai.ground.strong-score";
    /** Days after which an untouched material is suspected stale. */
    static final String KEY_STALE_WINDOW_DAYS = "ai.ground.stale-window-days";
    private static final int DEFAULT_STALE_WINDOW_DAYS = 7;
    /**
 * Defect corpus depth key : how many newest
 * defects of the asking project enter the candidate pool. Hot-updatable —
 * the seeded default below is only the fallback when the key is absent.
 */
    static final String KEY_DEFECT_CANDIDATES = "ai.chat.grounding.defect-candidates";

    private static final int DEFAULT_MAX_CITATIONS = 3;
    private static final int DEFAULT_EXCERPT_CHARS = 160;
    private static final double DEFAULT_MIN_SCORE = 0.05;
    private static final int DEFAULT_DEFECT_CANDIDATES = 8;

    /** Chapters whose title overlaps the question worth a detail fetch (bodies are not in the index). */
    private static final int HELP_DETAIL_BUDGET = 4;
    /** Candidate chunks pulled from the vector store before kernel truncation. */
    private static final int KNOWLEDGE_CANDIDATES = 6;

    private static final Logger log = LoggerFactory.getLogger(GroundingService.class);

    private final CoreGateway coreGateway;
    private final KnowledgeSearchService knowledgeSearchService;
    /** Ledger row reader for citation versioning (tp_knowledge_doc.revision). */
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate ledgerJdbc;
    /** Single-source usage-switch reader for the grounding decision (shared with case-gen). */
    private final com.sixbean.testpike.aiservice.knowledge.domain.component.DocumentUsageSwitchReader usageSwitches;

    public GroundingService(CoreGateway coreGateway,
                            KnowledgeSearchService knowledgeSearchService,
                            org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate ledgerJdbc) {
        this.coreGateway = coreGateway;
        this.knowledgeSearchService = knowledgeSearchService;
        this.ledgerJdbc = ledgerJdbc;
        this.usageSwitches = new com.sixbean.testpike.aiservice.knowledge.domain.component
                .DocumentUsageSwitchReader(ledgerJdbc);
    }

    /** Assemble citations + prompt-side context for one question. */
    public GroundingOutcome assemble(long projectId, String question) {
        // Legacy shape: no role verdict, kernel keeps its exact old behaviour.
        return assemble(projectId, question, true);
    }

    /**
 * Role-aware assembly : the vertical verdict
 * rides the run message (DTO field, admin session face) and is forwarded
 * to the kernel — the single enforcement point of the trim map.
 */
    public GroundingOutcome assemble(long projectId, String question, boolean systemManager) {
        if (question == null || question.isBlank()) {
            return GroundingOutcome.none();
        }
        List<GroundingHit> candidates = new ArrayList<>();
        candidates.addAll(helpCandidates(question));
        candidates.addAll(knowledgeCandidates(projectId, question));
        candidates.addAll(defectCandidates(projectId));
        return GroundingKernel.assemble(question, candidates,
                intConfig(KEY_MAX_CITATIONS, DEFAULT_MAX_CITATIONS),
                intConfig(KEY_EXCERPT_CHARS, DEFAULT_EXCERPT_CHARS),
                doubleConfig(KEY_MIN_SCORE, DEFAULT_MIN_SCORE), systemManager,
                doubleConfig(KEY_STRONG_SCORE, com.sixbean.testpike.aiservice.aichat.domain.component
                        .GroundingKernel.DEFAULT_STRONG_SCORE),
                intConfig(KEY_STALE_WINDOW_DAYS, DEFAULT_STALE_WINDOW_DAYS),
                java.time.OffsetDateTime.now());
    }

    /**
     * Help chapters ranked purely by title overlap with the question, then
     * detail-fetched (only the top few — the index carries no bodies, and one
     * HTTP call per domain keeps the turn cheap). A chapter whose domain
     * detail is missing falls back to its title-only shape (still citable).
     */
    private List<GroundingHit> helpCandidates(String question) {
        List<CoreGateway.HelpDocBrief> index;
        try {
            index = coreGateway.listHelpDocs();
        } catch (RuntimeException e) {
            log.warn("接地帮助索引不可达，本次零帮助候选 projectId 无关: {}", e.getMessage());
            return List.of();
        }
        Set<String> questionTerms = GroundingKernel.termsOf(question);
        List<CoreGateway.HelpDocBrief> matched = index.stream()
                .filter(doc -> doc.bodyMd() == null || !doc.bodyMd().isBlank())
                .sorted(Comparator.comparingInt(
                        (CoreGateway.HelpDocBrief doc) -> titleOverlap(questionTerms, doc)).reversed())
                .filter(doc -> titleOverlap(questionTerms, doc) > 0)
                .limit(HELP_DETAIL_BUDGET)
                .toList();

        List<GroundingHit> hits = new ArrayList<>();
        for (CoreGateway.HelpDocBrief chapter : matched) {
            if (chapter.bodyMd() != null && !chapter.bodyMd().isBlank()) {
                hits.add(toHit(chapter));
                continue;
            }
            List<CoreGateway.HelpDocBrief> detail;
            try {
                detail = coreGateway.getHelpDomainChapters(chapter.domainCode());
            } catch (RuntimeException e) {
                log.warn("接地帮助域详情不可达 domain={} {}", chapter.domainCode(), e.getMessage());
                detail = List.of();
            }
            CoreGateway.HelpDocBrief full = detail.stream()
                    .filter(d -> d.id() != null && d.id().equals(chapter.id())).findFirst()
                    .orElse(chapter);
            hits.add(toHit(full));
        }
        return hits;
    }

    /** Knowledge chunks via the same-process G2 service (project segment, BUSINESS scene). */
    private List<GroundingHit> knowledgeCandidates(long projectId, String question) {
        List<KnowledgeSearchItem> items;
        try {
            items = knowledgeSearchService.search(new KnowledgeSearchRequest(projectId,
                    question, KnowledgeSearchScene.BUSINESS.name(), KNOWLEDGE_CANDIDATES, null));
        } catch (RuntimeException e) {
            log.warn("接地知识检索不可达，本次零知识候选 projectId={}: {}", projectId, e.getMessage());
            return List.of();
        }
        // Usage-switch routing: documents switched off for grounding never
        // become grounding citations (single-source reader, fail-soft when the
        // ledger is unreachable; chunks with no doc_id stay in the candidate
        // set, matching the case-gen posture).
        items = filterGroundingOptedOut(items);
        List<GroundingHit> hits = new ArrayList<>();
        java.util.Map<Long, KnowledgeLedgerFacts> facts = ledgerFacts(items);
        for (KnowledgeSearchItem item : items == null ? List.<KnowledgeSearchItem>of() : items) {
            long refId = item.docId() == null ? 0L : item.docId();
            KnowledgeLedgerFacts fact = facts.get(refId);
            hits.add(new GroundingHit(GroundingKernel.KIND_KNOWLEDGE, refId, null,
                    item.title(), item.content(), item.score(),
                    fact == null ? null : fact.revision(), false,
                    fact == null ? null : fact.updatedAt(), item.chunkId()));
        }
        return hits;
    }

    /**
     * Drop hits whose ledger document switched off the grounding usage. The
     * decision asks the single-source reader only (no second retrieval chain
     * here); an empty opt-out set returns the same list untouched, keeping the
     * zero-drift posture for default-on documents.
     */
    private List<KnowledgeSearchItem> filterGroundingOptedOut(List<KnowledgeSearchItem> items) {
        if (items == null || items.isEmpty()) {
            return items;
        }
        Set<Long> optedOut = usageSwitches.optedOut(
                items.stream().map(KnowledgeSearchItem::docId)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toSet()),
                com.sixbean.testpike.aiservice.knowledge.domain.component
                        .DocumentUsageSwitchReader.COLUMN_USE_IN_GROUNDING);
        if (optedOut.isEmpty()) {
            return items;
        }
        return items.stream()
                .filter(item -> item.docId() == null || !optedOut.contains(item.docId()))
                .toList();
    }

    /**
 * Live defect corpus : the newest defects of the
 * asking project, read straight from {@code tp_defect} so the digital test
 * colleague answers from what actually broke here instead of a re-stated
 * copy (live source = fresh, no second write path, and the project segment is
 * the same H1 boundary every other read uses).
 * <p>Each row is published in up to two shapes of the SAME source row: the
 * plain history shape (title, severity, status, description) everyone in
 * the project may cite, and — only when the row really carries them — an
 * attribution shape that adds who fixes it and why it was closed, flagged
 * manager-only. {@link GroundingKernel} is the only place that decides
 * which shape survives, so this adapter never looks at the role itself.
 * <p>Failure posture matches the other sources: an unreachable defect
 * table costs this source, never the turn.
 */
    private List<GroundingHit> defectCandidates(long projectId) {
        List<java.util.Map<String, Object>> rows;
        try {
            rows = ledgerJdbc.queryForList(DEFECT_CORPUS_SQL,
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                            .addValue("projectId", projectId)
                            .addValue("depth", intConfig(KEY_DEFECT_CANDIDATES,
                                    DEFAULT_DEFECT_CANDIDATES)));
        } catch (RuntimeException e) {
            log.warn("接地缺陷语料不可达，本次零缺陷候选 projectId={}: {}", projectId, e.getMessage());
            return List.of();
        }
        List<GroundingHit> hits = new ArrayList<>();
        for (java.util.Map<String, Object> row : rows == null
                ? List.<java.util.Map<String, Object>>of() : rows) {
            GroundingHit plain = defectHit(row, false);
            if (plain != null) {
                hits.add(plain);
                GroundingHit attribution = defectHit(row, true);
                if (attribution != null) {
                    hits.add(attribution);
                }
            }
        }
        return hits;
    }

    /** Newest-first defect rows of one project; attribution columns stay unread for members. */
    private static final String DEFECT_CORPUS_SQL = """
            SELECT d.id, d.title, d.description, d.severity, d.status,
                   d.closed_reason, d.version, u.display_name AS assignee_name
              FROM tp_defect d
              LEFT JOIN tp_user u ON u.id = d.assignee_id
             WHERE d.project_id = :projectId
             ORDER BY d.updated_at DESC
             LIMIT :depth
            """;

    /**
     * Map one defect row into a grounding candidate. The attribution shape is
     * returned only when the row actually carries a fixer or a closing reason
     * (a plain defect with nobody assigned adds nothing vertical, so members
     * and managers see the same single card); the row's optimistic-lock
     * version rides as the citation revision, naming which state of the record
     * the answer stood on.
     */
    private static GroundingHit defectHit(java.util.Map<String, Object> row, boolean attribution) {
        Object rawId = row.get("id");
        Object rawTitle = row.get("title");
        if (!(rawId instanceof Number id) || !(rawTitle instanceof String title) || title.isBlank()) {
            return null;
        }
        Integer revision = row.get("version") instanceof Number version ? version.intValue() : null;
        StringBuilder text = new StringBuilder()
                .append("严重级别 ").append(DefectLabels.severityLabel(
                        row.get("severity") instanceof Number s ? s.intValue() : 0))
                .append('，').append(DefectLabels.statusLabel(
                        row.get("status") instanceof Number s ? s.intValue() : 0)).append('。');
        if (row.get("description") instanceof String description && !description.isBlank()) {
            text.append(description).append('。');
        }
        if (!attribution) {
            return new GroundingHit(GroundingKernel.KIND_DEFECT, id.longValue(), null,
                    title, text.toString(), 0.0, revision);
        }
        String assignee = row.get("assignee_name") instanceof String name && !name.isBlank()
                ? name : null;
        String closedReason = row.get("closed_reason") instanceof String reason && !reason.isBlank()
                ? reason : null;
        if (assignee == null && closedReason == null) {
            return null;
        }
        if (assignee != null) {
            text.append("修复人：").append(assignee).append('。');
        }
        if (closedReason != null) {
            text.append("关闭原因：").append(closedReason).append('。');
        }
        return new GroundingHit(GroundingKernel.KIND_DEFECT, id.longValue(), null,
                title, text.toString(), 0.0, revision, true);
    }

    /**
     * Batch-load ledger revisions for the candidate doc ids (one IN query, no
     * per-chunk round trip). Fail-soft by design: the revision is an audit
     * anchor, so an unreachable ledger leaves the hits unversioned instead of
     * dropping an otherwise grounded answer.
     */
    private java.util.Map<Long, KnowledgeLedgerFacts> ledgerFacts(List<KnowledgeSearchItem> items) {
        java.util.Map<Long, KnowledgeLedgerFacts> out = new java.util.HashMap<>();
        if (items == null || items.isEmpty()) {
            return out;
        }
        List<Long> ids = items.stream().map(KnowledgeSearchItem::docId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) {
            return out;
        }
        try {
            for (java.util.Map<String, Object> row : ledgerJdbc.queryForList(
                    "SELECT id, revision, updated_at FROM tp_knowledge_doc WHERE id IN (:ids)",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("ids", ids))) {
                Object id = row.get("id");
                if (!(id instanceof Number number)) {
                    continue;
                }
                Integer version = row.get("revision") instanceof Integer v ? v : null;
                out.put(number.longValue(),
                        new KnowledgeLedgerFacts(version, ledgerTimestamp(row.get("updated_at"))));
            }
        } catch (RuntimeException e) {
            log.warn("接地台账读取不可达，本次候选不携带 revision 与更新时间: {}", e.getMessage());
        }
        return out;
    }

    /** JDBC timestamp shapes across drivers -> one OffsetDateTime, absent = null. */
    private static java.time.OffsetDateTime ledgerTimestamp(Object raw) {
        if (raw instanceof java.time.OffsetDateTime value) {
            return value;
        }
        if (raw instanceof java.sql.Timestamp ts) {
            return ts.toInstant().atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        }
        if (raw instanceof java.time.LocalDateTime ldt) {
            return ldt.atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        }
        return null;
    }

    private static GroundingHit toHit(CoreGateway.HelpDocBrief chapter) {
        long refId = chapter.id() == null ? 0L : chapter.id();
        return new GroundingHit(GroundingKernel.KIND_HELP, refId, chapter.domainCode(),
                chapter.title(), chapter.bodyMd(), 0.0);
    }

    /** Count of question terms appearing in the chapter title. */
    private static int titleOverlap(Set<String> questionTerms, CoreGateway.HelpDocBrief doc) {
        Set<String> titleTerms = new LinkedHashSet<>(GroundingKernel.termsOf(doc.title()));
        long matched = titleTerms.stream().filter(questionTerms::contains).count();
        return (int) matched;
    }

    private int intConfig(String key, int fallback) {
        String raw = readConfig(key);
        try {
            return raw == null ? fallback : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private double doubleConfig(String key, double fallback) {
        String raw = readConfig(key);
        try {
            return raw == null ? fallback : Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private String readConfig(String key) {
        try {
            return coreGateway.getSysConfig(key);
        } catch (RuntimeException e) {
            // Config door down → seeded defaults; grounding must never fail a turn over a knob.
            log.warn("接地配置键读取失败 key={}: {}", key, e.getMessage());
            return null;
        }
    }
}
