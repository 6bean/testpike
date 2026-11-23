package com.sixbean.testpike.aiservice.aichat.domain.component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Retrieval-first grounding assembler .
 * <p>Pure function over flattened candidates (help chapters + knowledge
 * chunks + semantic-dictionary rows all map into {@link GroundingHit}) so the
 * ranking/dedup/excerpt/honesty rules live exactly once and are testable with
 * zero infrastructure. The service side fetches candidates and
 * passes K8-resolved budgets in.
 * <p>Contract under test:
 * <ul>
 * <li>final score = retrieval base score + lexical overlap of the question
 * with title (double-weighted) and body; CJK queries match via character
 * bigrams, latin via words;</li>
 * <li>hits below the minimum score are dropped — and when NOTHING survives,
 * the outcome is the honest zero-hit shape (no citations, no context block)
 * that the orchestrator turns into "I don't know, here is where to look",
 * never an improvised platform answer;</li>
 * <li>same source row twice (different chunks) collapses to its best hit;</li>
 * <li>at most {@code maxCitations} survive, best first, numbered from 1;</li>
 * <li>excerpts are windows around the first matched term, length-capped.</li>
 * </ul>
 * <p>Scoring model: a term of the question appearing in the title counts
 * double, in the body once; each side is a Jaccard-style ratio over the
 * question's term set, plus the retrieval-side base score, plus a small
 * family bonus for help chapters (product-semantics questions are usually
 * answerable straight from tp_help_doc). The floor is intentionally gentle —
 * relevance here decides "grounded at all", the cap decides "how much".
 */
public final class GroundingKernel {

    /** Help-document chapter family (tp_help_doc). */
    public static final String KIND_HELP = "help";
    /** Vector knowledge-base family (G1 chunks, project + global scope). */
    public static final String KIND_KNOWLEDGE = "knowledge";
    /**
 * Live defect-history family (tp_defect, AO batch S4 : what
 * broke in this project before. One row may arrive twice — the plain
 * history shape every project member may cite, and the attribution shape
 * (who fixes it, why it was closed) flagged {@code managerOnly}; dedup by
 * kind + refId then keeps the strongest shape the asker is allowed to see.
 */
    public static final String KIND_DEFECT = "defect";

    /** Ranking nudge for product help chapters over general knowledge chunks. */
    private static final double HELP_FAMILY_BONUS = 0.15;

    /** Shipped bar between high and weak relevance (K8 ai.ground.strong-score). */
    public static final double DEFAULT_STRONG_SCORE = 0.35;

    /** Header that introduces the naming lines inside the context block. */
    private static final String NAMING_HEADER = "—— 材料质量提示 ——";

    /** Minute-resolution as-of label carried by every stamped material pool. */
    private static final DateTimeFormatter MOMENT_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /**
 * side is a CJK/latin run (2–20 chars), the value side stops at whitespace
 * and sentence punctuation — deliberately narrow so prose that merely
 * mentions a word never poses as a structured assertion: false conflicts
 * are worse than silent ones.
 */
    private static final Pattern FIELD_ASSERT = Pattern.compile(
            "([\\u4e00-\\u9fffA-Za-z0-9_]{2,20})\\s*[：=]\\s*([^\\s,，。；;、\\n]{1,60})");

    /**
     * Rank, dedupe and truncate candidates against the question.
     *
     * @param question        user prompt the answer must be grounded in
     * @param candidates      flattened hits from all grounding sources
     * @param maxCitations    cap on surviving citations (K8 ai.chat.grounding.max-citations)
     * @param excerptChars    per-citation excerpt window budget (K8 ...excerpt-chars)
     * @param minScore        relevance floor (K8 ...min-score); below = not grounded
     * @return ranked citations + prompt-side context block, or the honest
     *         zero-hit outcome when nothing clears the floor
     */
    public static GroundingOutcome assemble(String question,
                                            List<GroundingHit> candidates,
                                            int maxCitations,
                                            int excerptChars,
                                            double minScore) {
        // Pre-identity-channel callers keep their exact old verdict (zero
        // drift): no role filter is applied to a pool assembled without one.
        return assemble(question, candidates, maxCitations, excerptChars, minScore, true);
    }

    /**
 * Role-aware assembly : the vertical trim map
 * lives HERE and nowhere else — a manager-only candidate is dropped from
 * the pool before ranking when the asking run is not a system-manager one,
 * so neither the citation frames nor the prompt-side context block can
 * ever carry responsibility facts past the role verdict. Members get the
 * honest zero-hit shape instead of a half-trimmed row.
 *
 * @param systemManager verdict carried on the run message (admin session
 * face, SystemManagerRoles single source)
 */
    public static GroundingOutcome assemble(String question,
                                            List<GroundingHit> candidates,
                                            int maxCitations,
                                            int excerptChars,
                                            double minScore,
                                            boolean systemManager) {
        // Legacy channel: strong bar at the shipped default, staleness off
        // (versionless callers never carry a timestamp anyway — a row without
        // one is structurally un-accusable, never silently judged stale).
        return assemble(question, candidates, maxCitations, excerptChars, minScore,
                systemManager, DEFAULT_STRONG_SCORE, 0, OffsetDateTime.now());
    }

    /**
 * Four-grade material assembly . On top of the
 * ranking contract above, the surviving pool is graded: evidence at or
 * over {@code strongScore} = high relevance, only above the floor = weak;
 * two different source rows asserting mutually exclusive values for the
 * predates {@code staleWindowDays} = suspected stale. Conflict outranks
 * stale (the sharper truth claim) yet both naming lines ride. The
 * citations themselves are byte-identical to the pre-grading verdict —
 * grading only annotates, never re-filters.
 *
 * @param strongScore bar separating high from weak relevance
 * (K8 ai.ground.strong-score)
 * @param staleWindowDays days after which an untouched material is
 * suspected stale; {@code <= 0} disables the check
 * (K8 ai.ground.stale-window-days)
 * @param now clock of the verdict, injected for testability
 */
    public static GroundingOutcome assemble(String question,
                                            List<GroundingHit> candidates,
                                            int maxCitations,
                                            int excerptChars,
                                            double minScore,
                                            boolean systemManager,
                                            double strongScore,
                                            int staleWindowDays,
                                            OffsetDateTime now) {
        Set<String> terms = termsOf(question);
        if (terms.isEmpty() || candidates == null || candidates.isEmpty() || maxCitations <= 0) {
            return GroundingOutcome.none();
        }

        // Dedupe by source row keeping the best-ranked candidate of that row.
        // Floor verdict uses evidence-based score only (base + lexical): the
        // family bonus ranks help chapters higher but can NEVER ground an
        // answer on its own — a chapter sharing no term with the question is
        // not relevant no matter which family it belongs to.
        Map<String, ScoredGroundingHit> best = new LinkedHashMap<>();
        for (GroundingHit hit : candidates) {
            if (hit.managerOnly() && !systemManager) {
                continue;
            }
            double evidence = hit.baseScore() + lexicalScore(terms, hit);
            if (evidence < minScore) {
                continue;
            }
            double score = evidence + (KIND_HELP.equals(hit.kind()) ? HELP_FAMILY_BONUS : 0.0);
            ScoredGroundingHit scored = new ScoredGroundingHit(hit, score);
            String key = hit.kind() + ":" + hit.refId();
            ScoredGroundingHit previous = best.get(key);
            if (previous == null || scored.score() > previous.score()) {
                best.put(key, scored);
            }
        }
        if (best.isEmpty()) {
            return GroundingOutcome.none();
        }

        List<ScoredGroundingHit> ranked = best.values().stream()
                .sorted(Comparator.comparingDouble(ScoredGroundingHit::score).reversed())
                .limit(maxCitations)
                .toList();

        List<GroundingCitation> citations = new ArrayList<>(ranked.size());
        StringBuilder block = new StringBuilder();
        double topEvidence = 0.0;
        for (int i = 0; i < ranked.size(); i++) {
            ScoredGroundingHit entry = ranked.get(i);
            GroundingHit hit = entry.hit();
            topEvidence = Math.max(topEvidence, hit.baseScore() + lexicalScore(terms, hit));
            String excerpt = excerptAround(terms,
                    hit.content() == null ? hit.title() : hit.content(), excerptChars);
            citations.add(new GroundingCitation(i + 1, hit.kind(), hit.refId(),
                    hit.domainCode(), hit.title(), excerpt, entry.score(), hit.revision(),
                    hit.chunkId()));
            block.append("[C").append(i + 1).append("] ")
                    .append(hit.title()).append(": ").append(excerpt).append('\n');
        }

        // Data-moment stamp (behaviour kernel round-2 item 5): the pool rides
        // ONE as-of line built from the ledger timestamps the retrieval
        // channel already read — zero extra fetch per turn. The most recent
        // write time names the pool; an all-untimed pool says so honestly
        // instead of inventing a clock (same time concept as the
        // time-travel replay block, shown on the output side).
        OffsetDateTime poolMoment = null;
        for (ScoredGroundingHit entry : ranked) {
            OffsetDateTime written = entry.hit().updatedAt();
            if (written != null && (poolMoment == null || written.isAfter(poolMoment))) {
                poolMoment = written;
            }
        }
        block.append(poolMoment == null
                ? "【数据时刻】本批材料未记录最后写入时间，据此作答时请说明时点未知，不得虚构。\n"
                : "【数据时刻】本批材料最近一次更新时间为 "
                        + MOMENT_FORMAT.format(poolMoment) + "，事实结论以此为截至时点。\n");

        // Grading annotates the pool, it never re-filters it: citations and
        // the zero-hit shape above are untouched, only the verdict fields
        // below are new .
        List<String> conflicts = conflictWarnings(ranked);
        List<String> stale = staleWarnings(ranked, staleWindowDays, now);
        GroundingQuality quality;
        if (!conflicts.isEmpty()) {
            quality = GroundingQuality.CONFLICT;
        } else if (!stale.isEmpty()) {
            quality = GroundingQuality.STALE_SUSPECT;
        } else if (topEvidence < strongScore) {
            quality = GroundingQuality.WEAK;
        } else {
            quality = GroundingQuality.HIGH;
        }
        List<String> warnings = new ArrayList<>(conflicts);
        warnings.addAll(stale);
        if (!warnings.isEmpty()) {
            block.append(NAMING_HEADER).append('\n');
            for (String line : warnings) {
                block.append(line).append('\n');
            }
        }
        return new GroundingOutcome(List.copyOf(citations), block.toString(), false,
                quality, List.copyOf(warnings), poolMoment);
    }

    /**
 * Cross-source contradictions: two different rows asserting different
 * cleared the floor speak, a row disagreeing with itself is content noise
 * the reader sees directly, not a verdict the platform should pick
 * through — agreement (same value) never warns.
 */
    private static List<String> conflictWarnings(List<ScoredGroundingHit> ranked) {
        Map<String, Map<String, String>> assertions = new LinkedHashMap<>();
        for (int i = 0; i < ranked.size(); i++) {
            GroundingHit hit = ranked.get(i).hit();
            if (hit.content() == null) {
                continue;
            }
            String source = "C" + (i + 1) + "《" + hit.title() + "》"
                    + "[" + hit.kind() + ":" + hit.refId() + "]";
            Matcher m = FIELD_ASSERT.matcher(hit.content());
            while (m.find()) {
                String value = m.group(2).trim().toLowerCase(Locale.ROOT);
                assertions.computeIfAbsent(m.group(1), k -> new LinkedHashMap<>())
                        .merge(value, source, (a, b) -> a.equals(b) ? a : a + " / " + b);
            }
        }
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, Map<String, String>> field : assertions.entrySet()) {
            Map<String, String> values = field.getValue();
            if (values.size() < 2) {
                continue;
            }
            Set<String> rows = new HashSet<>();
            for (String sources : values.values()) {
                for (String one : sources.split(" / ")) {
                    rows.add(one.substring(one.indexOf('[')));
                }
            }
            if (rows.size() < 2) {
                continue;
            }
            StringBuilder line = new StringBuilder("【材料相互矛盾】同一项「")
                    .append(field.getKey()).append("」在不同材料中取值互斥：");
            boolean first = true;
            for (Map.Entry<String, String> v : values.entrySet()) {
                if (!first) {
                    line.append("；");
                }
                line.append(v.getKey()).append("（出处 ").append(v.getValue()).append("）");
                first = false;
            }
            line.append("。回答时必须向用户点明这一分歧并列出两处出处，不得静默择一。");
            lines.add(line.toString());
        }
        return lines;
    }

    /**
     * Suspected-stale materials: the ledger timestamp predates the configured
     * window. A row carrying no timestamp is never accused (fail-open on the
     * honesty side — an absent clock proves nothing about drift).
     */
    private static List<String> staleWarnings(List<ScoredGroundingHit> ranked,
                                              int staleWindowDays, OffsetDateTime now) {
        List<String> lines = new ArrayList<>();
        if (staleWindowDays <= 0 || now == null) {
            return lines;
        }
        for (ScoredGroundingHit entry : ranked) {
            GroundingHit hit = entry.hit();
            if (hit.updatedAt() == null || !hit.updatedAt().isBefore(now.minusDays(staleWindowDays))) {
                continue;
            }
            long idleDays = java.time.temporal.ChronoUnit.DAYS.between(hit.updatedAt(), now);
            lines.add("【材料过期嫌疑】《" + hit.title() + "》最后更新于 "
                    + hit.updatedAt().toLocalDate() + "，距今约 " + idleDays
                    + " 天未更新，可能与近期变更不一致；引用它时请向用户注明建议再核实。");
        }
        return lines;
    }

    /** Question-term overlap: title counts double, body once, as set ratios. */
    private static double lexicalScore(Set<String> questionTerms, GroundingHit hit) {
        double title = overlap(questionTerms, termsOf(hit.title()), 1.0);
        double body = overlap(questionTerms, termsOf(hit.content()), 0.5);
        return title * 2.0 + body;
    }

    /** Fraction of {@code terms} found in {@code against} (weight-scaled). */
    private static double overlap(Set<String> terms, Set<String> against, double weight) {
        if (against.isEmpty()) {
            return 0.0;
        }
        long matched = terms.stream().filter(against::contains).count();
        return weight * matched / terms.size();
    }

    /**
     * Tokenize for overlap: latin/digit words lowercased, CJK runs expanded
     * into character bigrams (single chars only when the run length is 1) —
     * bigrams catch Chinese phrases without a segmentation dependency.
     */
    public static Set<String> termsOf(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> terms = new HashSet<>();
        Matcher latin = LATIN_WORD.matcher(text);
        while (latin.find()) {
            terms.add(latin.group().toLowerCase(Locale.ROOT));
        }
        Matcher cjk = CJK_RUN.matcher(text);
        while (cjk.find()) {
            String run = cjk.group();
            if (run.length() == 1) {
                terms.add(run);
            }
            for (int i = 0; i + 1 < run.length(); i++) {
                terms.add(run.substring(i, i + 2));
            }
        }
        return terms;
    }

    /**
     * Window of at most {@code budget} characters centered on the first term
     * found in the text (falling back to the head) — the citation card shows
     * the matched passage, never the whole document.
     */
    static String excerptAround(Set<String> terms, String text, int budget) {
        if (text == null) {
            return "";
        }
        if (budget <= 0 || text.length() <= budget) {
            return text;
        }
        int matchAt = -1;
        for (String term : terms) {
            int at = text.indexOf(term);
            if (at >= 0 && (matchAt < 0 || at < matchAt)) {
                matchAt = at;
            }
        }
        int start = matchAt < 0 ? 0
                : Math.max(0, Math.min(matchAt - budget / 3, text.length() - budget));
        return text.substring(start, start + budget);
    }

    private static final Pattern LATIN_WORD = Pattern.compile("[A-Za-z0-9_]+");
    private static final Pattern CJK_RUN = Pattern.compile("[\\u4e00-\\u9fff]+");

    private GroundingKernel() {
    }
}
