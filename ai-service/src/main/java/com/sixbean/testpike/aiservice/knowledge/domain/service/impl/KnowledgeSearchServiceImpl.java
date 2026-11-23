package com.sixbean.testpike.aiservice.knowledge.domain.service.impl;

import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeHitRecorder;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeLangResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeMetadataFields;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchConfigResolver;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchProperties;
import com.sixbean.testpike.aiservice.knowledge.domain.component.KnowledgeSearchScene;
import com.sixbean.testpike.aiservice.knowledge.domain.component.DocumentUsageSwitchReader;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;


@Service
public class KnowledgeSearchServiceImpl implements KnowledgeSearchService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeSearchServiceImpl.class);

    private final VectorStore vectorStore;
    private final KnowledgeSearchConfigResolver configResolver;
    private final KnowledgeSearchProperties properties;
    private final KnowledgeLangResolver langResolver;
    private final KnowledgeHitRecorder hitRecorder;
    /** Single-source usage-switch reader . */
    private final DocumentUsageSwitchReader usageSwitches;

    public KnowledgeSearchServiceImpl(VectorStore vectorStore,
                                      KnowledgeSearchConfigResolver configResolver,
                                      KnowledgeSearchProperties properties,
                                      KnowledgeLangResolver langResolver,
                                      KnowledgeHitRecorder hitRecorder,
                                      NamedParameterJdbcTemplate ledgerJdbc) {
        this.vectorStore = vectorStore;
        this.configResolver = configResolver;
        this.properties = properties;
        this.langResolver = langResolver;
        this.hitRecorder = hitRecorder;
        this.usageSwitches = new DocumentUsageSwitchReader(ledgerJdbc);
    }

    @Override
    public List<KnowledgeSearchItem> search(KnowledgeSearchRequest request) {
        if (request == null || request.projectId() == null) {
            throw new ServiceException("检索缺少 projectId（H5 项目隔离入参必填）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        if (request.query() == null || request.query().isBlank()) {
            throw new ServiceException("检索 query 不能为空", BizErrorCode.VALIDATION_ERROR);
        }
        KnowledgeSearchScene scene = parseScene(request.scene());
        int topK = request.topK() != null && request.topK() > 0
                ? request.topK() : scene.getDefaultTopK();
        double threshold = sceneThreshold(scene);

        List<Document> chainA = retrieve(request.projectId(), request.query(),
                properties.getInitialTopKProject(), threshold);
        List<Document> chainB = retrieve(0L, request.query(),
                properties.getInitialTopKGlobal(), threshold);

        Map<String, FusedChunk> fused = fuse(chainA, chainB);
        List<KnowledgeSearchItem> ranked = rerank(fused, request.projectId());
        ranked.sort((x, y) -> Double.compare(y.score(), x.score()));
        List<KnowledgeSearchItem> top = ranked.size() > topK
                ? new ArrayList<>(ranked.subList(0, topK)) : ranked;
        // Usage-switch routing: documents whose use-in-case-prompt switch is
        // off never reach the case-generation prompt. The decision sits after the
        // truncation and before the hit journal, so the recorded injection set
        // equals what case-gen really receives; chunks without a ledger row
        // (docId=null, born from cases/packs/styles) are never gated.
        if (scene == KnowledgeSearchScene.CASE_GEN) {
            List<Long> topDocIds = top.stream().map(KnowledgeSearchItem::docId)
                    .filter(java.util.Objects::nonNull).toList();
            Set<Long> optedOut = usageSwitches.optedOut(topDocIds,
                    DocumentUsageSwitchReader.COLUMN_USE_IN_CASE_PROMPT);
            if (!optedOut.isEmpty()) {
                top.removeIf(item -> item.docId() != null && optedOut.contains(item.docId()));
            }
        }
        hitRecorder.record(request.taskId(), scene.name(), top);
        return top;
    }

    
    private List<Document> retrieve(long projectId, String query, int limit, double threshold) {
        List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(limit)
                .similarityThreshold(threshold)
                .filterExpression(KnowledgeMetadataFields.PROJECT_ID + " == " + projectId
                        + " && " + KnowledgeMetadataFields.STATUS + " == '"
                        + KnowledgeMetadataFields.ACTIVE + "'")
                .build());
        return hits == null ? List.of() : hits;
    }

    
    private void accumulateRrf(Map<String, FusedChunk> fused, List<Document> endpoint, int k) {
        for (int rank = 0; rank < endpoint.size(); rank++) {
            Document doc = endpoint.get(rank);
            double inc = 1.0 / (k + rank + 1);
            fused.merge(doc.getId(), new FusedChunk(doc, inc),
                    (old, add) -> new FusedChunk(old.document(), old.score() + add.score()));
        }
    }

    
    private void accumulateWeighted(Map<String, FusedChunk> fused, List<Document> endpoint, double weight) {
        for (Document doc : endpoint) {
            double inc = weight * similarity(doc);
            fused.merge(doc.getId(), new FusedChunk(doc, inc),
                    (old, add) -> new FusedChunk(old.document(), Math.max(old.score(), add.score())));
        }
    }

    private Map<String, FusedChunk> fuse(List<Document> chainA, List<Document> chainB) {
        Map<String, FusedChunk> fused = new LinkedHashMap<>();
        if (configResolver.weightedMode()) {
            double gw = configResolver.globalWeight();
            accumulateWeighted(fused, chainA, 1.0 - gw);
            accumulateWeighted(fused, chainB, gw);
        } else {
            int k = configResolver.rrfK();
            accumulateRrf(fused, chainA, k);
            accumulateRrf(fused, chainB, k);
        }
        return fused;
    }

    
    private List<KnowledgeSearchItem> rerank(Map<String, FusedChunk> fused, long projectId) {
        String projectLang = langResolver.resolve(projectId);
        double excellent = configResolver.reweightExcellent();
        double spec = configResolver.reweightSpec();
        double feedback = configResolver.reweightFeedback();
        double langBonus = configResolver.langBonus();

        List<KnowledgeSearchItem> items = new ArrayList<>(fused.size());
        for (FusedChunk entry : fused.values()) {
            Document doc = entry.document();
            Map<String, Object> meta = doc.getMetadata();
            String sourceType = stringMeta(meta, KnowledgeMetadataFields.SOURCE_TYPE);
            boolean isExcellentCase = Boolean.TRUE.equals(meta.get(KnowledgeMetadataFields.EXCELLENT))
                    && KnowledgeMetadataFields.SOURCE_TYPE_TEST_CASE.equals(sourceType);
            double factor = sourceFactor(sourceType, isExcellentCase, excellent, spec, feedback);
            String lang = stringMeta(meta, KnowledgeMetadataFields.LANG);
            boolean langMatch = lang != null && projectLang != null
                    && lang.equalsIgnoreCase(projectLang);
            items.add(new KnowledgeSearchItem(
                    doc.getId(),
                    longMeta(meta, KnowledgeMetadataFields.DOC_ID),
                    longMeta(meta, KnowledgeMetadataFields.CASE_ID),
                    sourceType,
                    stringMeta(meta, KnowledgeMetadataFields.TITLE),
                    doc.getText(),
                    similarity(doc),
                    entry.score() * factor + (langMatch ? langBonus : 0.0)));
        }
        return items;
    }

    private static double sourceFactor(String sourceType, boolean excellentCase,
                                       double excellent, double spec, double feedback) {
        if (excellentCase) {
            return excellent;
        }
        if (KnowledgeMetadataFields.SOURCE_TYPE_TEST_SPEC.equals(sourceType)
                || "PACK_KNOWLEDGE".equals(sourceType) || "STYLE".equals(sourceType)) {
            return spec;
        }
        if ("FEEDBACK".equals(sourceType)) {
            return feedback;
        }
        return 1.0;
    }

    
    private double sceneThreshold(KnowledgeSearchScene scene) {
        Double override = switch (scene) {
            case CASE_GEN -> properties.getCaseGenThreshold();
            case SIMILARITY -> properties.getSimilarityThreshold();
            default -> null;
        };
        return override != null ? override : scene.getDefaultThreshold();
    }

    private static KnowledgeSearchScene parseScene(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ServiceException("检索场景 scene 必填（CASE_GEN/SIMILARITY/LOCATOR/BUSINESS/FALLBACK）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        try {
            return KnowledgeSearchScene.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ServiceException("未知检索场景 " + raw + "（允许 CASE_GEN/SIMILARITY/LOCATOR/BUSINESS/FALLBACK）",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    private static double similarity(Document doc) {
        Double score = doc.getScore();
        if (score == null) {
            log.warn("G2 命中缺 similarity score chunkId={}（回退 1.0，理论不达）", doc.getId());
            return 1.0;
        }
        return score;
    }

    private static String stringMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v == null ? null : String.valueOf(v);
    }

    private static Long longMeta(Map<String, Object> meta, String key) {
        Object v = meta.get(key);
        return v instanceof Number n ? n.longValue() : null;
    }
}
