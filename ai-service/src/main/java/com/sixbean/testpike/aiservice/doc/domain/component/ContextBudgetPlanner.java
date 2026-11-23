package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.llm.domain.component.PromptSizeGuard;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;


public class ContextBudgetPlanner {

    private static final org.slf4j.Logger INTAKE_LOG =
            org.slf4j.LoggerFactory.getLogger(ContextBudgetPlanner.class);

    
    private final com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator tokenCounter;

    
    private final BudgetPolicy policy;

 
    public static final String BUDGET_CONFIG_KEY = "ai.doc.token-budget";

 
    public static final int DEFAULT_TOKEN_BUDGET = 5000;

    // K8 key for the grouping overlap window. Adjacent groups share a segment of
    // boundary content so a requirement point cut across the seam stays visible
    // in both groups; the shared segment is charged into the next group's budget.
    // The write side of the key validates the same window (E8 guard) and the
    // reader fails closed to the factory value on garbage/out-of-window input.
    public static final String KEY_GROUP_OVERLAP_RATIO = "ai.doc.group-overlap-ratio";
    public static final double DEFAULT_OVERLAP_RATIO = 0.10;
    public static final double MIN_OVERLAP_RATIO = 0.0;
    public static final double MAX_OVERLAP_RATIO = 0.25;

    // : grouping granularity decoupled from the model window (ruling C1:
    // the window decides WHETHER a single send is allowed, never how coarsely
    // the node reads). Node caps are hot K8 keys, factory values from the
    // calibration report: one group stays within group-max-tokens AND
    // group-max-chunks; the final per-request output estimate stays within
    // bucket-output-max-tokens once multiplied by the measured output
    // amplification ratio. Read side fails closed to the factory value and the
    // caller names the key; the write side validates the same window.
    public static final String KEY_GROUP_MAX_TOKENS = "ai.doc.group-max-tokens";
    public static final int DEFAULT_GROUP_MAX_TOKENS = 8000;
    public static final int MIN_GROUP_MAX_TOKENS = 256;
    public static final int MAX_GROUP_MAX_TOKENS = 100000;

    public static final String KEY_GROUP_MAX_CHUNKS = "ai.doc.group-max-chunks";
    public static final int DEFAULT_GROUP_MAX_CHUNKS = 45;
    public static final int MIN_GROUP_MAX_CHUNKS = 1;
    public static final int MAX_GROUP_MAX_CHUNKS = 500;

    /**
 * residual face: chapter-span cap on the grouping plane. Golden
 * window red A showed a node may legally ride 35+ tiny chapters while
 * staying inside the token AND chunk caps, and the model then truncated
 * the enumeration at the group tail (chapters 86-120 / 246-280 lost).
 * The span of distinct chapters per sealed group gets its own hot key.
 */
    public static final String KEY_GROUP_MAX_CHAPTERS = "ai.doc.group-max-chapters";
    public static final int DEFAULT_GROUP_MAX_CHAPTERS = 20;
    public static final int MIN_GROUP_MAX_CHAPTERS = 1;
    public static final int MAX_GROUP_MAX_CHAPTERS = 1000;

    public static final String KEY_BUCKET_OUTPUT_MAX_TOKENS = "ai.doc.bucket-output-max-tokens";
    // recalibration (was 24000, welded on the pre-recall output face):
    // the exhaustive-recall clauses legitimately inflate one call's billed
    // completion (thinking tokens included) — measured on the live chain:
    // p90 34157 / p95 39940 / p99 47843, observed max 69136 (225 plane rows
    // after the booking-seam cut). 24000 kept reading red as a stale ceiling,
    // and the routed thinking model bills past a published max_tokens, so the
    // audit line must track the measured form instead of the input x 2.94
    // prediction. 72000 = observed max with margin, inside the 1000..200000
    // write-side window; grouping granularity is unaffected (the derived
    // input ceiling 72000/2.94 stays above the 8000 node cap).
    public static final int DEFAULT_BUCKET_OUTPUT_MAX_TOKENS = 72000;
    public static final int MIN_BUCKET_OUTPUT_MAX_TOKENS = 1000;
    public static final int MAX_BUCKET_OUTPUT_MAX_TOKENS = 200000;

    // Output amplification = completion tokens per prompt token, MEASURED on the
    // live chain ; the fidelity ledger re-feeds this
    // face, it is never welded to a guess. Out-of-window values fail closed.
    public static final String KEY_OUTPUT_AMPLIFICATION = "ai.doc.output-amplification-ratio";
    public static final double DEFAULT_OUTPUT_AMPLIFICATION = 2.94;
    public static final double MIN_OUTPUT_AMPLIFICATION = 0.1;
    public static final double MAX_OUTPUT_AMPLIFICATION = 10.0;

    // : chapter-aligned grouping — a sealed group must never weld two
    // chapters together unless a single chapter alone busts the node caps.
    // The boundary is judged ahead of the budget/chunk-count face inside the
    // SAME greedy loop (no second implementation); the overlap tail keeps
    // riding across the seam. Factory ON: only an explicit "false" restores
    // the legacy budget-only greedy, byte-identical to the pre- grouping
    // face. Write side validates the bool form in core ConfigServiceImpl;
    // the reader fails closed to ON. The final-bucket plane is NOT touched.
    public static final String KEY_CHAPTER_ALIGNED_GROUPING =
            "ai.doc.chapter-aligned-grouping.enabled";

    // K8 key capping ONE final-extraction request . Empty or
    // absent = automatic ceiling = effective budget x DEFAULT_BUCKET_HEADROOM;
    // 0 disables bucketing, keeping the legacy single monolithic call exactly
    // as before. Write side rejects negatives and garbage with a plain 400;
    // the reader falls back to the automatic face and names the key in the log.
    public static final String KEY_FINAL_BUCKET_MAX_TOKENS = "ai.doc.final-bucket-max-tokens";

    /** Factory headroom applied to the effective budget when no explicit ceiling is stored. */
    public static final double DEFAULT_BUCKET_HEADROOM = 0.6;

    /** Automatic final-bucket ceiling from the effective budget (rounded tokens). */
    public static int autoBucketMaxTokens(int effectiveBudgetTokens) {
        return (int) Math.round(effectiveBudgetTokens * DEFAULT_BUCKET_HEADROOM);
    }

    /** Read-side face for the final-bucket ceiling: null/blank = null (caller
     *  uses the automatic face); a parseable non-negative number answers itself
     *  (0 = bucketing disabled); negative/garbage = null so the chain degrades
     *  to automatic instead of aborting (the caller names the key in a WARN).
     *  The core write side pins the same rule inline in ConfigServiceImpl
     *  (module direction forbids importing this class there, IT-XQ-03
     *  double-ended contract keeps both faces aligned). */
    public static Integer finalBucketMaxOf(String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(rawValue.trim());
            return parsed >= 0 ? parsed : null;
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private final int budgetChars;

    private final int tokenBudget;

    /** Overlap window ratio in force (0 = legacy byte-identical grouping). */
    private final double overlapRatio;

    /** node caps in force (factory defaults when no K8 row is stored). */
    private final int groupMaxTokens;
    private final int groupMaxChunks;
    /** Chapter-span cap in force (0 = cap disabled, legacy shape byte-identical). */
    private final int groupMaxChapters;
    private final int bucketOutputMaxTokens;
    private final double outputAmplification;

    /** chapter-boundary face in force (false = legacy greedy, byte-identical). */
    private final boolean chapterAligned;

    
    public ContextBudgetPlanner(int tokenBudget) {
        this(tokenBudget, null, null);
    }

 
    private final int effectiveTokenBudget;

    private ContextBudgetPlanner(int tokenBudget,
                                 com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator counter,
                                 BudgetPolicy budgetPolicy) {
        this(tokenBudget, counter, budgetPolicy, tokenBudget, 0.0);
    }

    private ContextBudgetPlanner(int tokenBudget,
                                 com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator counter,
                                 BudgetPolicy budgetPolicy, int effectiveTokenBudget,
                                 double overlapRatio) {
        this(tokenBudget, counter, budgetPolicy, effectiveTokenBudget, overlapRatio,
                DEFAULT_GROUP_MAX_TOKENS, DEFAULT_GROUP_MAX_CHUNKS, 0,
                DEFAULT_BUCKET_OUTPUT_MAX_TOKENS, DEFAULT_OUTPUT_AMPLIFICATION, false);
    }

    private ContextBudgetPlanner(int tokenBudget,
                                 com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator counter,
                                 BudgetPolicy budgetPolicy, int effectiveTokenBudget,
                                 double overlapRatio, int groupMaxTokens, int groupMaxChunks,
                                 int groupMaxChapters,
                                 int bucketOutputMaxTokens, double outputAmplification,
                                 boolean chapterAligned) {
        this.tokenBudget = tokenBudget;
        this.budgetChars = tokenBudget * 480 / 512;
        this.tokenCounter = counter;
        this.policy = budgetPolicy;
        this.effectiveTokenBudget = effectiveTokenBudget;
        this.overlapRatio = Math.max(MIN_OVERLAP_RATIO, Math.min(MAX_OVERLAP_RATIO, overlapRatio));
        this.groupMaxTokens = clamp(groupMaxTokens, MIN_GROUP_MAX_TOKENS, MAX_GROUP_MAX_TOKENS);
        this.groupMaxChunks = clamp(groupMaxChunks, MIN_GROUP_MAX_CHUNKS, MAX_GROUP_MAX_CHUNKS);
        this.groupMaxChapters = groupMaxChapters <= 0
                ? 0 : clamp(groupMaxChapters, MIN_GROUP_MAX_CHAPTERS, MAX_GROUP_MAX_CHAPTERS);
        this.bucketOutputMaxTokens = clamp(bucketOutputMaxTokens,
                MIN_BUCKET_OUTPUT_MAX_TOKENS, MAX_BUCKET_OUTPUT_MAX_TOKENS);
        this.outputAmplification = outputAmplification <= 0 || Double.isNaN(outputAmplification)
                ? DEFAULT_OUTPUT_AMPLIFICATION
                : Math.max(MIN_OUTPUT_AMPLIFICATION, Math.min(MAX_OUTPUT_AMPLIFICATION, outputAmplification));
        this.chapterAligned = chapterAligned;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
 * A1 : the min rule itself lives in PromptSizeGuard only. */
    public ContextBudgetPlanner withEffectiveBudget(int effectiveTokens) {
        int eff = PromptSizeGuard.effectiveLimit(tokenBudget, effectiveTokens);
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, policy, eff, overlapRatio,
                groupMaxTokens, groupMaxChunks, groupMaxChapters, bucketOutputMaxTokens,
                outputAmplification, chapterAligned);
    }

    
    public ContextBudgetPlanner withTokenCounter(
            com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator counter) {
        return new ContextBudgetPlanner(tokenBudget, counter, policy,
                effectiveTokenBudget, overlapRatio,
                groupMaxTokens, groupMaxChunks, groupMaxChapters, bucketOutputMaxTokens,
                outputAmplification, chapterAligned);
    }

    
    public ContextBudgetPlanner withPolicy(BudgetPolicy budgetPolicy) {
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, budgetPolicy,
                effectiveTokenBudget, overlapRatio,
                groupMaxTokens, groupMaxChunks, groupMaxChapters, bucketOutputMaxTokens,
                outputAmplification, chapterAligned);
    }

    /** Assemble the grouping overlap window (value-object style, same ratio face
     *  consumed later by the chapter bucketing of the final extraction call —
     *  one overlap function for both planes, no second implementation). */
    public ContextBudgetPlanner withOverlapRatio(double ratio) {
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, policy,
                effectiveTokenBudget, ratio,
                groupMaxTokens, groupMaxChunks, groupMaxChapters, bucketOutputMaxTokens,
                outputAmplification, chapterAligned);
    }

    /** assemble the node caps (value-object style like the overlap face).
 * The grouping plane and the direct-send decision consume min(window,
 * groupMaxTokens); groupMaxChunks bounds the chunk count of one node. */
    public ContextBudgetPlanner withGroupCeilings(int maxTokens, int maxChunks,
                                                  int outputMaxTokens, double amplification) {
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, policy,
                effectiveTokenBudget, overlapRatio,
                maxTokens, maxChunks, groupMaxChapters, outputMaxTokens, amplification,
                chapterAligned);
    }

    /** assemble the chapter-boundary face (value-object style, same
 * discipline as the overlap and ceiling faces). true = seal a group where
 * the chapter changes; false = the legacy budget-only greedy. */
    public ContextBudgetPlanner withChapterAligned(boolean aligned) {
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, policy,
                effectiveTokenBudget, overlapRatio,
                groupMaxTokens, groupMaxChunks, groupMaxChapters, bucketOutputMaxTokens,
                outputAmplification, aligned);
    }

    /**
 * residual face: assemble the chapter-span cap (value-object style,
 * same discipline as the ceiling faces). The grouping plane seals a group
 * the moment adding a chunk from a NEW chapter would push its distinct
 * chapter count past this cap — never inside a single chapter (a chapter
 * that alone busts the token/chunk caps keeps splitting by those caps).
 * 0 = cap disabled; every legacy/default face stays byte-identical.
 */
    public ContextBudgetPlanner withGroupMaxChapters(int maxChapters) {
        return new ContextBudgetPlanner(tokenBudget, tokenCounter, policy,
                effectiveTokenBudget, overlapRatio,
                groupMaxTokens, groupMaxChunks, maxChapters, bucketOutputMaxTokens,
                outputAmplification, chapterAligned);
    }

    /** Chapter-span cap in force (0 = disabled). */
    public int groupMaxChapters() {
        return groupMaxChapters;
    }

    /** Chapter-boundary face in force (the summarize label renders through it). */
    public boolean chapterAligned() {
        return chapterAligned;
    }

    /** grouping budget = min(window budget, node token cap): the window
 * alone may never decide how coarsely a node reads (ruling C1).
 * A1 : delegates to the single min rule — no second copy. */
    public int groupBudgetTokens() {
        return PromptSizeGuard.effectiveLimit(effectiveTokenBudget, groupMaxTokens);
    }

    /** Character-face sibling of {@link #groupBudgetTokens}. */
    public int groupBudgetChars() {
        return Math.min(budgetChars, tokensToChars(groupMaxTokens));
    }

    public int groupMaxChunks() {
        return groupMaxChunks;
    }

    /**
 * output-side half of the dual constraint: cap a per-request input
 * ceiling so the estimated output (input x measured amplification) stays
 * within ai.doc.bucket-output-max-tokens. 0/negative passes through — the
 * caller's "bucketing disabled" legacy face stays byte-identical.
 */
    public int capByOutputCeiling(int inputCeilingTokens) {
        if (inputCeilingTokens <= 0) {
            return inputCeilingTokens;
        }
        int outputDerived = (int) (bucketOutputMaxTokens / outputAmplification);
        return Math.min(inputCeilingTokens, outputDerived);
    }

    /** generation-side face: the run's resolved output ceiling
 * (ai.doc.bucket-output-max-tokens, clamped into window) — the plane
 * seam publishes it as the request's max_tokens so no call can bill a
 * completion past the D6 audit line. */
    public int bucketOutputMaxTokens() {
        return bucketOutputMaxTokens;
    }

    
    public BudgetPolicy policyOrDefault() {
        return policy == null ? BudgetPolicy.defaults() : policy;
    }

    
    public int tokenBudget() {
        return effectiveTokenBudget;
    }

    
    public int rawTokenBudget() {
        return tokenBudget;
    }

    
    public static ContextBudgetPlanner fromConfig(String rawTokenBudget) {
        return new ContextBudgetPlanner(tokenBudgetOf(rawTokenBudget));
    }

    
    public static int tokenBudgetOf(String rawTokenBudget) {
        if (rawTokenBudget != null && !rawTokenBudget.isBlank()) {
            try {
                int parsed = Integer.parseInt(rawTokenBudget.trim());
                if (parsed > 0) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_TOKEN_BUDGET;
    }

    
    public static int budgetCharsOf(String rawTokenBudget) {
        return tokenBudgetOf(rawTokenBudget) * 480 / 512;
    }

    
    public boolean needsCompression(List<Map<String, Object>> chunks) {
        return totalChars(chunks) > groupBudgetChars();
    }

    
    public List<List<Map<String, Object>>> planGroups(List<Map<String, Object>> chunks) {
        return plan(chunks, groupBudgetChars(), groupMaxChunks, groupMaxChapters,
                chunk -> contentOf(chunk).length(), chapterAligned, true);
    }

    /**
 * Shared greedy loop behind both planes (character and token) — one
 * implementation, one overlap function, so the grouping plane and the
 * chapter-bucket plane of the final extraction call can never drift apart.
 * maxChunks &gt; 0 bounds the chunk count of one sealed group ( node
 * cap); 0 = count-unbounded, the pre- bucketing face.
 */
    private List<List<Map<String, Object>>> plan(List<Map<String, Object>> chunks,
                                                 int budget,
                                                 int maxChunks,
                                                 int maxChapters,
                                                 java.util.function.ToIntFunction<Map<String, Object>> sizeOf,
                                                 boolean chapterBoundary,
                                                 boolean seamSealsOnlyAtCap) {
        int overlapBudget = (int) (overlapRatio * budget);
        List<List<Map<String, Object>>> groups = new ArrayList<>();
        List<Map<String, Object>> current = new ArrayList<>();
        int currentSize = 0;
        String lastChapter = null;
        // Distinct chapters riding in the current group (overlap-carried chunks
        // count towards the span of the group they were carried into).
        Set<String> currentChapters = new java.util.LinkedHashSet<>();
        for (Map<String, Object> chunk : chunks) {
            int size = sizeOf.applyAsInt(chunk);
            // residual face: the chapter-span cap seals on a NEW
            // chapter only once the group already rides maxChapters distinct
            // chapters. It never splits inside one chapter (budget handles
            // oversized chapters) and stays inert on the legacy faces
            // (maxChapters 0 or the chapter plane off), byte-identical.
            boolean spanCapReached = maxChapters > 0 && chapterBoundary
                    && !current.isEmpty()
                    && !currentChapters.contains(chapterOf(chunk))
                    && currentChapters.size() >= maxChapters;
            // Chapter boundary is judged ahead of budget/count, but every face
            // shares the ONE seal action. Only chunks natively added here move
            // the anchor: an overlap tail carried from the sealed group must
            // not re-seal the very next chunk on the far side of the seam.
            // root cause F1 (grouping plane only): a chapter seam
            // seals only a group that has REACHED its caps — adjacent small
            // chapters keep loading into the same group (the 460-tiny-chapter
            // face used to weld one group per chapter and burned the golden
            // window; the ruled §nine shape is ~16 groups at 8,000 tokens /
            // 45 chunks). The chapter-bucket plane of the final extraction
            // call keeps its ruled seam-always posture (chapter provenance,
            // never weld chapters that each fit the ceiling) — pinned by
            // ContextBudgetChapterBucketsTest, switched per call site below.
            boolean boundary = chapterBoundary && lastChapter != null
                    && !lastChapter.equals(chapterOf(chunk))
                    && (!seamSealsOnlyAtCap || currentSize + size > budget
                        || (maxChunks > 0 && current.size() >= maxChunks));
            // Seal a non-empty group when the chapter changed, adding this
            // chunk would bust the budget, or the node chunk cap is reached;
            // a single oversized chunk stays alone (current empty guard).
            if (!current.isEmpty() && (boundary || spanCapReached
                    || currentSize + size > budget
                    || (maxChunks > 0 && current.size() >= maxChunks))) {
                List<Map<String, Object>> sealed = current;
                groups.add(sealed);
                current = new ArrayList<>(overlapTail(sealed, sizeOf, overlapBudget));
                currentSize = current.stream().mapToInt(sizeOf).sum();
                lastChapter = null;
                currentChapters = new java.util.LinkedHashSet<>();
                for (Map<String, Object> carried : current) {
                    currentChapters.add(chapterOf(carried));
                }
            }
            current.add(chunk);
            currentSize += size;
            if (chapterBoundary) {
                lastChapter = chapterOf(chunk);
                currentChapters.add(chapterOf(chunk));
            }
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
    }

    /**
     * Single-source overlap tail: the longest suffix of a sealed group whose
     * cumulative size stays within the overlap budget. Consumed here and by the
     * chapter bucketing of the final extraction call — no second copy allowed.
     */
    static <T> List<T> overlapTail(List<T> sealedGroup, java.util.function.ToIntFunction<T> sizeOf,
                                   int overlapBudget) {
        List<T> tail = new ArrayList<>();
        int acc = 0;
        for (int i = sealedGroup.size() - 1; i >= 0; i--) {
            int size = sizeOf.applyAsInt(sealedGroup.get(i));
            if (acc + size > overlapBudget) {
                break;
            }
            tail.add(0, sealedGroup.get(i));
            acc += size;
        }
        return tail;
    }

    
    public static double overlapRatioOf(String rawRatio) {
        if (rawRatio != null && !rawRatio.isBlank()) {
            try {
                double parsed = Double.parseDouble(rawRatio.trim());
                if (parsed >= MIN_OVERLAP_RATIO && parsed <= MAX_OVERLAP_RATIO) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return DEFAULT_OVERLAP_RATIO;
    }

    
    public static boolean acceptsOverlapRatio(String rawRatio) {
        if (rawRatio == null || rawRatio.isBlank()) {
            return false;
        }
        try {
            double parsed = Double.parseDouble(rawRatio.trim());
            return parsed >= MIN_OVERLAP_RATIO && parsed <= MAX_OVERLAP_RATIO;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    // ── node-cap faces (reader fail-closed + write-side window share
    //    ONE interval helper, so both ends can never drift apart) ──────────

    /** Read side: null/blank/garbage/out-of-window → factory value (the
     *  caller names the key in a WARN for non-blank garbage, same discipline
     *  as the overlap-ratio face). */
    static int intInWindowOf(String raw, int factory, int min, int max) {
        if (raw != null && !raw.isBlank()) {
            try {
                int parsed = Integer.parseInt(raw.trim());
                if (parsed >= min && parsed <= max) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
            }
        }
        return factory;
    }

    /** Write side of the same keys: legal = parseable int inside the window. */
    static boolean acceptsIntInWindow(String raw, int min, int max) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    static double doubleInWindowOf(String raw, double factory, double min, double max) {
        if (raw != null && !raw.isBlank()) {
            try {
                double parsed = Double.parseDouble(raw.trim());
                if (parsed >= min && parsed <= max) {
                    return parsed;
                }
            } catch (NumberFormatException ignored) {
                // fail-closed to factory, caller names the key
            }
        }
        return factory;
    }

    static boolean acceptsDoubleInWindow(String raw, double min, double max) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        try {
            double parsed = Double.parseDouble(raw.trim());
            return parsed >= min && parsed <= max;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    public static int groupMaxTokensOf(String raw) {
        return intInWindowOf(raw, DEFAULT_GROUP_MAX_TOKENS, MIN_GROUP_MAX_TOKENS, MAX_GROUP_MAX_TOKENS);
    }

    /** Reader face of the chapter-span cap (fail-closed to the factory 20). */
    public static int groupMaxChaptersOf(String raw) {
        return intInWindowOf(raw, DEFAULT_GROUP_MAX_CHAPTERS,
                MIN_GROUP_MAX_CHAPTERS, MAX_GROUP_MAX_CHAPTERS);
    }

    /** Write side of the same key: legal = parseable int inside the window. */
    public static boolean acceptsGroupMaxChapters(String raw) {
        return acceptsIntInWindow(raw, MIN_GROUP_MAX_CHAPTERS, MAX_GROUP_MAX_CHAPTERS);
    }

    public static boolean acceptsGroupMaxTokens(String raw) {
        return acceptsIntInWindow(raw, MIN_GROUP_MAX_TOKENS, MAX_GROUP_MAX_TOKENS);
    }

    public static int groupMaxChunksOf(String raw) {
        return intInWindowOf(raw, DEFAULT_GROUP_MAX_CHUNKS, MIN_GROUP_MAX_CHUNKS, MAX_GROUP_MAX_CHUNKS);
    }

    public static boolean acceptsGroupMaxChunks(String raw) {
        return acceptsIntInWindow(raw, MIN_GROUP_MAX_CHUNKS, MAX_GROUP_MAX_CHUNKS);
    }

    public static int bucketOutputMaxTokensOf(String raw) {
        return intInWindowOf(raw, DEFAULT_BUCKET_OUTPUT_MAX_TOKENS,
                MIN_BUCKET_OUTPUT_MAX_TOKENS, MAX_BUCKET_OUTPUT_MAX_TOKENS);
    }

    public static boolean acceptsBucketOutputMaxTokens(String raw) {
        return acceptsIntInWindow(raw, MIN_BUCKET_OUTPUT_MAX_TOKENS, MAX_BUCKET_OUTPUT_MAX_TOKENS);
    }

    public static double outputAmplificationOf(String raw) {
        return doubleInWindowOf(raw, DEFAULT_OUTPUT_AMPLIFICATION,
                MIN_OUTPUT_AMPLIFICATION, MAX_OUTPUT_AMPLIFICATION);
    }

    public static boolean acceptsOutputAmplification(String raw) {
        return acceptsDoubleInWindow(raw, MIN_OUTPUT_AMPLIFICATION, MAX_OUTPUT_AMPLIFICATION);
    }

    
    private static long totalChars(List<Map<String, Object>> chunks) {
        long total = 0;
        for (Map<String, Object> chunk : chunks) {
            total += contentOf(chunk).length();
        }
        return total;
    }

    
    public static String contentOf(Map<String, Object> chunk) {
        Object content = chunk.get("content");
        return content == null ? "" : String.valueOf(content);
    }

    /** Chapter of one chunk (missing/null = empty string — one reader, the
     *  same face the summarize plane already renders, no second rule). */
    public static String chapterOf(Map<String, Object> chunk) {
        Object chapter = chunk.get("chapter");
        return chapter == null ? "" : String.valueOf(chapter);
    }

    /**
 * node label of one sealed group. Legacy face (flag off) = the
 * first chunk's chapter plus the merge marker, byte-identical to the
 * pre- text. Aligned face lists every distinct chapter of the node in
 * order, so a node that legitimately spans chapters (one chapter busting
 * the node caps) never hides the chapters riding inside it.
 */
    public static String renderGroupChapterLabel(List<Map<String, Object>> group,
                                                 boolean chapterAligned) {
        if (!chapterAligned) {
            String first = chapterOf(group.get(0));
            return group.size() > 1 ? first + "（合并 " + group.size() + " 个分块）" : first;
        }
        List<String> distinct = new ArrayList<>();
        for (Map<String, Object> chunk : group) {
            String chapter = chapterOf(chunk);
            if (!distinct.contains(chapter)) {
                distinct.add(chapter);
            }
        }
        String label = String.join("、", distinct);
        return group.size() > 1 ? label + "（合并 " + group.size() + " 个分块）" : label;
    }


    
    public static int tokensToChars(int tokens) {
        return tokens * 480 / 512;
    }

    
    public int tokensOf(Map<String, Object> chunk) {
        String content = contentOf(chunk);
        if (tokenCounter != null) {
            return tokenCounter.countTokens(content);
        }
        return (int) Math.ceil(content.length() * 512.0 / 480);
    }

    /**
 * : grouped-intake planner face for the spec-extract and judge
 * QA-generation doors (R40 single source — the doors seal the SAME
 * token-bounded groups as the document plane instead of rejecting
 * over-sized chunk sets outright). Every cap reads the door's OWN
 * governance namespace ({@code keyPrefix} + group-max-tokens /
 * group-max-chunks / group-overlap-ratio) and fails closed: a missing
 * row answers the factory value silently, garbage or out-of-window
 * answers the factory value and names the key in the WARN. An unknown
 * tokenizer family degrades to the shared char face instead of taking
 * the door down.
 */
    public static ContextBudgetPlanner intakePlanner(int effectiveTokens,
                                                     String tokenizerFamily, String model,
                                                     java.util.function.Function<String, String> configReader,
                                                     String keyPrefix) {
        com.sixbean.testpike.aiservice.llm.domain.component.TokenEstimator estimator;
        try {
            estimator = com.sixbean.testpike.aiservice.llm.domain.component
                    .TokenEstimatorRegistry.resolve(tokenizerFamily, model);
        } catch (IllegalArgumentException e) {
            INTAKE_LOG.warn("封组摄入 tokenizer 族不可识别，退化字符口径：family={} cause={}",
                    tokenizerFamily, e.getMessage());
            estimator = null;
        }
        String rawOverlap = configReader.apply(keyPrefix + "group-overlap-ratio");
        if (rawOverlap != null && !rawOverlap.isBlank() && !acceptsOverlapRatio(rawOverlap)) {
            INTAKE_LOG.warn("封组摄入重叠比例越界或非法，fail-closed 回退出厂值 {}：key={} 原值={}",
                    DEFAULT_OVERLAP_RATIO, keyPrefix + "group-overlap-ratio", rawOverlap);
        }
        String rawMaxTokens = configReader.apply(keyPrefix + "group-max-tokens");
        if (rawMaxTokens != null && !rawMaxTokens.isBlank()
                && !acceptsGroupMaxTokens(rawMaxTokens)) {
            INTAKE_LOG.warn("封组摄入组 token 上限越界或非法，fail-closed 回退出厂值 {}：key={} 原值={}",
                    DEFAULT_GROUP_MAX_TOKENS, keyPrefix + "group-max-tokens", rawMaxTokens);
        }
        String rawMaxChunks = configReader.apply(keyPrefix + "group-max-chunks");
        if (rawMaxChunks != null && !rawMaxChunks.isBlank()
                && !acceptsGroupMaxChunks(rawMaxChunks)) {
            INTAKE_LOG.warn("封组摄入组块数上限越界或非法，fail-closed 回退出厂值 {}：key={} 原值={}",
                    DEFAULT_GROUP_MAX_CHUNKS, keyPrefix + "group-max-chunks", rawMaxChunks);
        }
        return new ContextBudgetPlanner(effectiveTokens)
                .withTokenCounter(estimator)
                .withOverlapRatio(overlapRatioOf(rawOverlap))
                .withGroupCeilings(groupMaxTokensOf(rawMaxTokens), groupMaxChunksOf(rawMaxChunks),
                        DEFAULT_BUCKET_OUTPUT_MAX_TOKENS, DEFAULT_OUTPUT_AMPLIFICATION);
    }

    
    public boolean needsCompressionByTokens(List<Map<String, Object>> chunks) {
        return totalTokens(chunks) > groupBudgetTokens();
    }

    
    public List<List<Map<String, Object>>> planGroupsByTokens(List<Map<String, Object>> chunks) {
        return plan(chunks, groupBudgetTokens(), groupMaxChunks, groupMaxChapters,
                this::tokensOf, chapterAligned, true);
    }

    /**
 * segment B — final-extraction chapter buckets (token face): the
 * SAME greedy loop and overlap tail as the grouping plane, only the
 * per-request ceiling differs, so both planes can never drift apart.
 */
    public List<List<Map<String, Object>>> planBucketsByTokens(int bucketTokenBudget,
                                                               List<Map<String, Object>> chunks) {
        return plan(chunks, bucketTokenBudget, 0, 0, this::tokensOf, false, false);
    }

    /** Character-face sibling of {@link #planBucketsByTokens} (no token engine wired). */
    public List<List<Map<String, Object>>> planBucketsByChars(int bucketCharBudget,
                                                              List<Map<String, Object>> chunks) {
        return plan(chunks, bucketCharBudget, 0, 0, chunk -> contentOf(chunk).length(), false, false);
    }

    /**
 * : the chapter-bucket face of the final extraction call. The
 * together while each chapter alone fits under the ceiling — the same
 * shared greedy loop ({@link #plan}, single source) runs with the
 * chapter-boundary predicate in force. The face follows the planner's
 * {@code chapterAligned} posture: with the hot key off the answer is
 * byte-identical to {@link #planBucketsByTokens} (legacy budget-only
 * shape stays reproducible). Only a single chapter busting the ceiling
 * may split inside its own chapter — never across two.
 */
    public List<List<Map<String, Object>>> planChapterBucketsByTokens(int bucketTokenBudget,
                                                                      List<Map<String, Object>> chunks) {
        return plan(chunks, bucketTokenBudget, 0, 0, this::tokensOf, chapterAligned, false);
    }

    /** Character-face sibling of {@link #planChapterBucketsByTokens}. */
    public List<List<Map<String, Object>>> planChapterBucketsByChars(int bucketCharBudget,
                                                                     List<Map<String, Object>> chunks) {
        return plan(chunks, bucketCharBudget, 0, 0, chunk -> contentOf(chunk).length(),
                chapterAligned, false);
    }

    /** Total token size of a chunk set (bucket trigger, token face). */
    public long totalTokensOf(List<Map<String, Object>> chunks) {
        return totalTokens(chunks);
    }

    /** Total character size of a chunk set (bucket trigger, character face). */
    public long totalCharsOf(List<Map<String, Object>> chunks) {
        return totalChars(chunks);
    }

    
    public int summaryBudgetChars(int groupBudgetTokens) {
        return tokensToChars(policyOrDefault().summaryBudgetTokens(groupBudgetTokens));
    }

    
    private long totalTokens(List<Map<String, Object>> chunks) {
        long total = 0;
        for (Map<String, Object> chunk : chunks) {
            total += tokensOf(chunk);
        }
        return total;
    }
}
