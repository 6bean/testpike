package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigDoubles;
import com.sixbean.testpike.common.exception.ServiceException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * <p> delivers that semantic upgrade: with the
 * {@link SemanticMatcher} wired, each criterion is reconciled against the
 * case aggregate text by embedding cosine (hot keys
 * {@code ai.gen.coverage-semantic.enabled} — factory on — and
 * {@code ai.gen.coverage-semantic-threshold}, both read fresh per check).
 * The fragment rule stays the degraded shape: when the embedding chain fails
 * the WHOLE check falls back to it with one WARN naming the task (zero
 * drift, generation never blocks on the reconcile), and the off switch or a
 * null collaborator reproduces the M1 verdict exactly.
 *
 */
public class CoverageChecker {

    private static final Logger log = LoggerFactory.getLogger(CoverageChecker.class);

    
    private static final int KEYWORD_LEN = 6;

    /** K8 hot key: semantic reconciliation master switch (factory on). */
    static final String SEMANTIC_ENABLED_KEY = "ai.gen.coverage-semantic.enabled";

    /** K8 hot key: cosine verdict line, seeded 0.82, accepted 0.5..0.99.
     *  Public: the rule ledger reconciles with this SAME floor (one line,
     *  no second threshold, R40). */
    public static final String SEMANTIC_THRESHOLD_KEY = "ai.gen.coverage-semantic-threshold";

    public static final double DEFAULT_THRESHOLD = 0.82;

    public static final double THRESHOLD_MIN = 0.5;

    public static final double THRESHOLD_MAX = 0.99;

    /** null = no config reads available → the checker stays on the legacy shape. */
    private final CoreGateway coreGateway;

    /** null = not wired → legacy fragment verdict (3a constructor convention). */
    private final SemanticMatcher semanticMatcher;

    /** Legacy shape without collaborators (test wiring only — Spring always
     *  takes the wired bean constructor below). */
    public CoverageChecker() {
        this(null, null);
    }

    public CoverageChecker(CoreGateway coreGateway, SemanticMatcher semanticMatcher) {
        this.coreGateway = coreGateway;
        this.semanticMatcher = semanticMatcher;
    }

    /**
     * @param projectId embedding routing anchor (cost/model selection); unused
     *                  on the legacy path
     * @param taskId    only names the degrade WARN — generation logs stay traceable
     */
    public CoverageResult check(String acceptanceCriteria, String caseText,
                                long projectId, long taskId) {
        List<String> criteria = split(acceptanceCriteria);
        if (criteria.isEmpty()) {
            return new CoverageResult(true, true, 0, List.of());
        }
        if (semanticMatcher != null && coreGateway != null
                && SysConfigBooleans.parse(coreGateway.getSysConfig(SEMANTIC_ENABLED_KEY), true)) {
            double threshold = SysConfigDoubles.parse(
                    coreGateway.getSysConfig(SEMANTIC_THRESHOLD_KEY),
                    DEFAULT_THRESHOLD, THRESHOLD_MIN, THRESHOLD_MAX);
            try {
                return semanticVerdict(criteria, caseText, projectId, threshold);
            } catch (ServiceException unavailable) {
                // fail-open degrade: the verdict itself never disappears, only
                // this round's reconciliation precision is lost (zero drift)
                log.warn("语义覆盖对账不可用，回落片段命中旧判据 taskId={} projectId={} 原因={}",
                        taskId, projectId, unavailable.getMessage());
            }
        }
        return fragmentVerdict(criteria, caseText);
    }

    /** Semantic verdict: cosine of each criterion against the aggregate text
     *  at or above the line = covered; one batched round-trip answers all. */
    private CoverageResult semanticVerdict(List<String> criteria, String caseText,
                                           long projectId, double threshold) {
        List<Double> similarities = semanticMatcher.similarities(criteria, caseText, projectId);
        int covered = 0;
        List<String> uncovered = new ArrayList<>();
        for (int i = 0; i < criteria.size(); i++) {
            if (similarities.get(i) >= threshold) {
                covered++;
            } else {
                uncovered.add(criteria.get(i));
            }
        }
        return new CoverageResult(uncovered.isEmpty(), false, covered, uncovered);
    }

    /** M1 fragment verdict (the degraded and switched-off shape, semantics unchanged). */
    private CoverageResult fragmentVerdict(List<String> criteria, String caseText) {
        String normalizedText = normalize(caseText);
        int covered = 0;
        List<String> uncovered = new ArrayList<>();
        for (String item : criteria) {
            if (matches(item, normalizedText)) {
                covered++;
            } else {
                uncovered.add(item);
            }
        }
        return new CoverageResult(uncovered.isEmpty(), false, covered, uncovered);
    }

    
    private boolean matches(String item, String normalizedText) {
        String compact = normalize(item);
        if (compact.isEmpty()) {
            return true;
        }
        List<String> fragments = new ArrayList<>();
        String head = compact.substring(0, Math.min(KEYWORD_LEN, compact.length()));
        fragments.add(head);
        if (compact.length() > KEYWORD_LEN) {
            String tail = compact.substring(compact.length() - KEYWORD_LEN);
            if (!tail.equals(head)) {
                fragments.add(tail);
            }
        }
        for (String frag : fragments) {
            if (normalizedText.contains(frag)) {
                return true;
            }
        }
        return false;
    }

    
    private List<String> split(String criteria) {
        List<String> result = new ArrayList<>();
        if (criteria == null || criteria.isBlank()) {
            return result;
        }
        for (String line : criteria.split("[\\n;；]")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    
    private String normalize(String text) {
        return text == null ? "" : text.replaceAll("\\s+", "");
    }
}
