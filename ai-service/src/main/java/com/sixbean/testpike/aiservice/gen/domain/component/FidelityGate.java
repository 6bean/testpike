package com.sixbean.testpike.aiservice.gen.domain.component;

import com.sixbean.testpike.aiservice.llm.pipeline.NumberProvenance;
import com.sixbean.testpike.common.util.JsonOutputExtractor;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Faithfulness checker : after generation, the
 * key claims of a case — quantities, boundary words, state words, negation
 * words — are re-verified against the original source blocks. A claim the
 * source does not carry becomes a named dispute, so the caller can feed it
 * back for one targeted regeneration round (round count is the K8 key
 * {@code ai.gen.fidelity-recheck-rounds}, wired at the generation chain).
 * <p>Design rules:
 * <ul>
 * <li>Quantities reuse the kernel's shared {@link NumberProvenance} shape
 * rule (ordinal/year exemptions come from there — never a second
 * regex, R40). Provenance itself is digit-run strict: "5" is not
 * backed by "15", otherwise a tampered figure would slip through a
 * substring match.</li>
 * <li>Boundary/state/negation words are audited as word families: a claim
 * in a family passes when the source carries at least one marker of
 * the same family. Family-level support is deliberately conservative
 * — comparing wording across families (not exceeding vs. maximum)
 * would invent disputes the source text cannot settle.</li>
 * <li>No source blocks = nothing to compare: the verdict is a pass with
 * zero audited claims, honestly reported so the ratio never looks
 * better than the evidence.</li>
 * </ul>
 * <p>Pure text, no I/O, no model call — deterministic and unit-testable;
 * the semantic upgrade of the A5 coverage checker (KB embedding) is the
 * next segment of the same task and must not be duplicated here.
 */
public class FidelityGate {

    /** Boundary family: limit and threshold wording. */
    private static final List<String> BOUNDARY_WORDS =
            List.of("不超过", "不低于", "不少于", "不大于", "不小于", "至少", "最多", "最少",
                    "最大", "最小", "超过", "低于", "以上", "以下", "以内", "范围");

    /** State family: lifecycle / permission state wording. */
    private static final List<String> STATE_WORDS =
            List.of("锁定", "解锁", "启用", "禁用", "停用", "生效", "失效", "过期", "冻结",
                    "已支付", "未支付", "在售", "下架");

    
    private static final List<String> NEGATION_WORDS =
            List.of("禁止", "不允许", "不得", "不能", "无法", "不可", "严禁");

    /** Sentence split for claim units. */
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("[\\n。；;！!？?]+");

    /** Raw digit runs of a text, the provenance pool for quantity comparison. */
    private static final Pattern DIGIT_RUN = Pattern.compile("\\d+(?:\\.\\d+)?");

    /** Quoted control names in step wording (「X」). */
    private static final Pattern QUOTED_CONTROL = Pattern.compile("「([^」\\n]{1,40})」");

    /** {@code "text":"X"} action locators — control names on the assertion face. */
    private static final Pattern TEXT_LOCATOR =
            Pattern.compile("\"text\"\\s*:\\s*\"([^\"]{1,40})\"");

    /**
     * Audit one generated text against the source blocks it was generated from.
     *
     * @param caseText    the generated case aggregate (steps + expected result + ...)
     * @param sourceBlocks original text blocks (requirement text / evidence chunks);
     *                     blank entries are ignored
     */
    public FidelityVerdict verdict(String caseText, List<String> sourceBlocks) {
        List<String> sources = new ArrayList<>();
        if (sourceBlocks != null) {
            for (String block : sourceBlocks) {
                if (block != null && !block.isBlank()) {
                    sources.add(block);
                }
            }
        }
        if (caseText == null || caseText.isBlank() || sources.isEmpty()) {
            // Nothing auditable — pass with zero claims, ratio 1.0 by definition.
            return new FidelityVerdict(true, List.of(), 0, 0, 1.0);
        }
        String joinedSource = String.join("\n", sources);
        List<String> disputes = new ArrayList<>();
        int checked = 0;
        int matched = 0;
        for (String sentence : SENTENCE_SPLIT.split(caseText)) {
            if (sentence.isBlank()) {
                continue;
            }
            // Claim 1: every quantity-shaped figure must exist in the source.
            for (String mention : NumberProvenance.findQuantityMentions(sentence)) {
                String figure = mention.split(" ")[0];
                checked++;
                if (digitRunPresent(joinedSource, figure)) {
                    matched++;
                } else {
                    disputes.add("数值断言无原文依据：" + mention + "（句：「" + sentence.trim() + "」）");
                }
            }
            // Claim 2: a boundary claim is supported when the source carries
            // synonym of one limit, not a dispute; direction cross-comparison
            // would invent disputes wording alone cannot settle).
            int[] boundary = auditFamily(sentence, joinedSource, markersIn(sentence, BOUNDARY_WORDS),
                    BOUNDARY_WORDS, "边界词", disputes);
            // wording, the negation semantics survive either way.
            int[] negation = auditFamily(sentence, joinedSource, markersIn(sentence, NEGATION_WORDS),
                    NEGATION_WORDS, "否定词", disputes);
            // are opposite states inside one family, so family support is
            // deliberately not enough here.
            int[] state = auditVerbatim(sentence, joinedSource, markersIn(sentence, STATE_WORDS),
                    "状态词", disputes);
            checked += boundary[0] + negation[0] + state[0];
            matched += boundary[1] + negation[1] + state[1];
        }
        double ratio = checked == 0 ? 1.0 : (double) matched / checked;
        return new FidelityVerdict(disputes.isEmpty(), List.copyOf(disputes), checked, matched, ratio);
    }

    /** Distinct family markers appearing in a text, generation order kept. */
    private Set<String> markersIn(String text, List<String> family) {
        Set<String> present = new LinkedHashSet<>();
        for (String word : family) {
            if (text.contains(word)) {
                present.add(word);
            }
        }
        return present;
    }

    /**
     * Family rule: one claim per generated marker; the whole family counts as
     * matched when the source carries any marker of the same family, every
     * marker becomes a named dispute when it carries none.
     *
     * @return {@code int[]{checked, matched}}
     */
    private int[] auditFamily(String sentence, String joinedSource, Set<String> markers,
                              List<String> family, String familyLabel, List<String> disputes) {
        if (markers.isEmpty()) {
            return new int[]{0, 0};
        }
        boolean supported = family.stream().anyMatch(joinedSource::contains);
        if (supported) {
            return new int[]{markers.size(), markers.size()};
        }
        for (String word : markers) {
            disputes.add(familyLabel + "无原文依据：「" + word + "」（句：「" + sentence.trim() + "」）");
        }
        return new int[]{markers.size(), 0};
    }

    /**
     * One claim per marker; a marker matches only when verbatim in the source.
     *
     * @return {@code int[]{checked, matched}}
     */
    private int[] auditVerbatim(String sentence, String joinedSource, Set<String> markers,
                                String familyLabel, List<String> disputes) {
        int count = 0;
        int hits = 0;
        for (String word : markers) {
            count++;
            if (joinedSource.contains(word)) {
                hits++;
            } else {
                disputes.add(familyLabel + "无原文依据：「" + word + "」（句：「" + sentence.trim() + "」）");
            }
        }
        return new int[]{count, hits};
    }

    /** Digit-run strict contains: "5" is not backed by "15". */
    private boolean digitRunPresent(String source, String figure) {
        Matcher matcher = DIGIT_RUN.matcher(source);
        while (matcher.find()) {
            if (matcher.group().equals(figure)) {
                return true;
            }
        }
        return false;
    }

    /**
 * Control-reference family ( segment, adjudication item 6 tail —
 * the same gate, no second loop component, R40). A step wording that
 * quotes a control («「X」」 or a text locator {@code "text":"X"}) must
 * reference a control the page-inspect probe actually saw: every distinct
 * reference is backed when the candidate segment carries it verbatim as a
 * quoted candidate name or a {@code text=} locator value.
 * <p>No candidate segment = no existence evidence: pass with zero audited
 * claims (probe off / fail-soft never invents a ghost reference — the same
 * honest rule as the empty-source shape above).
 */
    public FidelityVerdict controlRefs(String draftText, String candidateSegment) {
        return controlRefs(draftText, candidateSegment, false);
    }

    /** Marker the draft must carry when a step value has no evidence source. */
    public static final String HONEST_MARK = "需人工精化";

    /** URL-shaped claims in a step (goto targets with no probe / contract backing). */
    private static final Pattern URL_CLAIM = Pattern.compile("https?://[^\\s\"'\\\\}]+");

    /** Expected-value literals: any "expected*":"..." field in a step object. */
    private static final Pattern EXPECTED_LITERAL =
            Pattern.compile("\"expected[^\"]*\"\\s*:\\s*\"([^\"]{2,})\"");

    /**
 * Control-reference family with the honest-marking channel.
 * <p>When the candidate segment is present the backed/unbacked rule above
 * decides alone — the honest mark never whitelists a ghost name against
 * real evidence. When the segment is ABSENT there is no existence evidence
 * at all: with {@code noTruthHonestMark} off this is the legacy shape
 * (zero audited claims, silent pass); on, every step chunk carrying
 * same discipline the SQL steps already keep for an empty retrieval —
 * unmarked invented values become named disputes feeding the existing
 * targeted-revision feedback (no second loop).
 */
    public FidelityVerdict controlRefs(String draftText, String candidateSegment,
                                       boolean noTruthHonestMark) {
        if (draftText == null || draftText.isBlank()) {
            return new FidelityVerdict(true, List.of(), 0, 0, 1.0);
        }
        boolean segmentBlank = candidateSegment == null || candidateSegment.isBlank();
        if (!segmentBlank) {
            return backedControlRefs(draftText, candidateSegment);
        }
        if (!noTruthHonestMark) {
            return new FidelityVerdict(true, List.of(), 0, 0, 1.0);
        }
        List<String> disputes = new ArrayList<>();
        int checked = 0;
        int matched = 0;
        for (String step : stepChunks(draftText)) {
            List<String> claims = evidencelessClaimsIn(step);
            if (claims.isEmpty()) {
                continue;
            }
            checked += claims.size();
            if (step.contains(HONEST_MARK)) {
                matched += claims.size();
            } else {
                disputes.add("无据步骤含定位/URL/期望值未标「" + HONEST_MARK + "」："
                        + String.join("、", claims)
                        + "（探查候选、契约与元素台账均未命中；请为步骤补标「" + HONEST_MARK
                        + "」或改用有据的值）");
            }
        }
        if (checked == 0) {
            return new FidelityVerdict(true, List.of(), 0, 0, 1.0);
        }
        double ratio = (double) matched / checked;
        return new FidelityVerdict(disputes.isEmpty(), List.copyOf(disputes),
                checked, matched, ratio);
    }

    /**
 * Confidence-side honest-mark signal : how many step chunks of
 * the same primitives the dispute channel walks — one source, no second
 * rule (R40): a marked claim must never read differently for the loop
 * and for the confidence level. A mark without any claim is not a hit
 * (honest absence is not punished), an unmarked claim is a dispute, not
 * a confidence signal.
 */
    public static int honestMarkHits(String draftText) {
        if (draftText == null || draftText.isBlank()) {
            return 0;
        }
        int hits = 0;
        for (String step : stepChunks(draftText)) {
            if (!evidencelessClaimsIn(step).isEmpty() && step.contains(HONEST_MARK)) {
                hits++;
            }
        }
        return hits;
    }

    /** The historic backed/unbacked audit against a present candidate segment. */
    private FidelityVerdict backedControlRefs(String draftText, String candidateSegment) {
        Set<String> references = new LinkedHashSet<>();
        Matcher quoted = QUOTED_CONTROL.matcher(draftText);
        while (quoted.find()) {
            references.add(quoted.group(1));
        }
        Matcher locator = TEXT_LOCATOR.matcher(draftText);
        while (locator.find()) {
            references.add(locator.group(1));
        }
        if (references.isEmpty()) {
            return new FidelityVerdict(true, List.of(), 0, 0, 1.0);
        }
        List<String> disputes = new ArrayList<>();
        int matched = 0;
        for (String name : references) {
            if (candidateSegment.contains("「" + name + "」")
                    || candidateSegment.contains("text=" + name)) {
                matched++;
            } else {
                disputes.add("控件引用无候选依据：「" + name + "」（探查清单未见该控件，"
                        + "请改用候选中的名称与定位符）");
            }
        }
        int checked = references.size();
        double ratio = (double) matched / checked;
        return new FidelityVerdict(disputes.isEmpty(), List.copyOf(disputes),
                checked, matched, ratio);
    }

    /**
     * Step chunks of a JSON draft: every balanced object carrying a
     * {@code "description"} field with no nested description-bearing object.
     * Braces inside string literals are ignored so locator payloads with
     * braces cannot break the scan.
     */
    private static List<String> stepChunks(String draft) {
        List<int[]> spans = JsonOutputExtractor.balancedObjectSpans(draft);
        List<String> chunks = new ArrayList<>();
        for (int[] span : spans) {
            String content = draft.substring(span[0], span[1] + 1);
            if (!content.contains("\"description\"")) {
                continue;
            }
            boolean hasNestedStep = false;
            for (int[] inner : spans) {
                if (inner[0] > span[0] && inner[1] < span[1]
                        && draft.substring(inner[0], inner[1] + 1).contains("\"description\"")) {
                    hasNestedStep = true;
                    break;
                }
            }
            if (!hasNestedStep) {
                chunks.add(content);
            }
        }
        return chunks;
    }

    /** URL / locator / expected-value literal claims carried by one step. */
    private static List<String> evidencelessClaimsIn(String stepChunk) {
        List<String> claims = new ArrayList<>();
        Matcher url = URL_CLAIM.matcher(stepChunk);
        while (url.find()) {
            claims.add(url.group());
        }
        Matcher quoted = QUOTED_CONTROL.matcher(stepChunk);
        while (quoted.find()) {
            claims.add("「" + quoted.group(1) + "」");
        }
        Matcher locator = TEXT_LOCATOR.matcher(stepChunk);
        while (locator.find()) {
            claims.add("text=" + locator.group(1));
        }
        Matcher expected = EXPECTED_LITERAL.matcher(stepChunk);
        while (expected.find()) {
            claims.add("期望值「" + expected.group(1) + "」");
        }
        return claims;
    }
}
