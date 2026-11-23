package com.sixbean.testpike.aiservice.llm.pipeline;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;

/**
 * Single source for the exhaustive-recall clause block .
 * Diagnosis behind this file: enumeration/recall-type LLM tasks (requirement
 * extraction, spec-contract extraction, judge question generation, scenario
 * enumeration, DDL dictionary, mock-draft generation) shipped with
 * "summarise the key points" wording and no count contract, so small inputs
 * settled at the provider's summarisation habit (~5-9 items, 7 the mode) and
 * group-level caches pinned the number across reruns. These four clauses
 * state the opposite contract: dig every testable behaviour per block, never
 * merge independent behaviours, no upper bound on item count, declare an
 * empty block honestly instead of inventing items.
 * <p>Consumers append {@link #block} to the role/output-structure section
 * of their system prompt while keeping their own role and input-isolation
 * paragraphs verbatim (R40: the clause text exists exactly once in the
 * repository). The write-side switch and its fail-soft default live with
 * each consumer, not here.
 * <p>{@link #fingerprint(String)} gives the prompt-face generation tag the
 * disk caches mix into their key, so editing any clause re-burns instead of
 * silently reusing pinned items.
 */
public final class ExhaustiveRecallDirectives {

    /** ① Dig per block: every testable behaviour/constraint/exception/acceptance criterion, each numbered sentence its own item. */
    public static final String CLAUSE_DIG_EVERY_BEHAVIOUR =
            "逐块挖掘每一条可测试行为、约束、异常与验收标准，块内每条编号句独立成条；";

    /** ② Never merge independent behaviours into a summary point. */
    public static final String CLAUSE_NO_MERGING =
            "禁把多条独立行为合并成要点，禁「提炼主要」式收敛；";

    /** ③ No upper bound: the output count follows the source density. */
    public static final String CLAUSE_COUNT_FOLLOWS_SOURCE =
            "条数不设上限，产出多少由原文密度决定；";

    /** ④ A block with nothing extractable must be declared empty with a reason — never invent items. */
    public static final String CLAUSE_EMPTY_BLOCK_DECLARED =
            "某块确无可提取需求时显式声明空块及理由，禁编造。";

    /** The four clauses in contract order — literal-anchored by the contract test. */
    public static final List<String> CLAUSES = List.of(
            CLAUSE_DIG_EVERY_BEHAVIOUR,
            CLAUSE_NO_MERGING,
            CLAUSE_COUNT_FOLLOWS_SOURCE,
            CLAUSE_EMPTY_BLOCK_DECLARED);

    private static final String BLOCK = "【穷尽召回】" + String.join("", CLAUSES);

    private ExhaustiveRecallDirectives() {
    }

    /** The assembled recall paragraph consumers append to their system prompt. */
    public static String block() {
        return BLOCK;
    }

    /** Enabled → the prompt grows by exactly the block; disabled → byte-identical input. */
    public static String appendTo(String prompt, boolean enabled) {
        return enabled ? prompt + BLOCK : prompt;
    }

    /**
     * First 12 hex chars of SHA-256 — the prompt-face generation tag mixed
     * into disk-cache keys. Fixed width keeps the suffixed token inside the
     * VARCHAR(64) gen_token column while staying collision-free for the
     * handful of prompt variants this repository holds.
     */
    public static String fingerprint(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) {
                if (hex.length() >= 12) {
                    break;
                }
                hex.append(String.format("%02x", b));
            }
            return hex.substring(0, 12);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is required by the JLS", e);
        }
    }
}
