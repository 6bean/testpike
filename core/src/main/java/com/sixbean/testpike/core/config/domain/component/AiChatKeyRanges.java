package com.sixbean.testpike.core.config.domain.component;

import java.util.Map;
import java.util.Set;

/**
 * Write-side value ranges for the ai.chat.* dialog config key group (AO batch,
 * seeded by V296).
 *
 * <p>Why this class exists: the dialog transport budgets (first-frame budget,
 * heartbeat interval) are consumed live by the SSE orchestration segment — an
 * out-of-range value typed on the config page would either freeze the first
 * frame or flood heartbeats. Following the K8-key convention (no DB CHECK,
 * service layer guards), this table is the single source of ranges; the
 * reader side still falls back to defaults on garbage values (fail-closed).
 *
 * <p>Keys without a stated range in the adjudication sheet (session-budget-tokens,
 * stream-idle-timeout-seconds) are deliberately NOT interval-guarded here —
 * readers clamp (session-budget-tokens is now consumed: the dialog worker's
 * turn-boundary gate reads it once per turn and treats absent/unparsable as
 * unlimited, so a wild value there only changes the cap, never refuses
 * everything). exec-enabled is a bool switch guarded by the bool face below.
 */
public final class AiChatKeyRanges {

    /** Int keys the group interval-guards: key → {lower, upper}. */
    private static final Map<String, int[]> INT_RANGES = Map.ofEntries(
            Map.entry("ai.chat.first-frame-budget-seconds", new int[]{2, 30}),
            Map.entry("ai.chat.heartbeat-seconds", new int[]{2, 60}),
            Map.entry("ai.chat.grounding.max-citations", new int[]{1, 10}),
            Map.entry("ai.chat.grounding.excerpt-chars", new int[]{40, 600}),
            // Multi-step plan card: estimated tool calls at or above this value
            // make the turn emit a plan frame before running; below it the turn
            // runs straight away so short questions never get a card in the way.
            Map.entry("ai.chat.plan-threshold", new int[]{2, 10}),
            // Knife 4b: per-question @-mention chip cap, read hot by ChatRunServiceImpl.
            Map.entry("ai.chat.ref.max-objects", new int[]{1, 20}),
            // : autonomous goal-plan continuation cap — batches one dialog
            // turn may pick up on its own before it stops and reports honestly.
            Map.entry("ai.chat.goal.max-auto-batches", new int[]{1, 10}),
            // pinned-case grounding: how many newest review opinions /
            // failed runs enter a case chip's digest block (bounds the
            // prompt-weight of one chip; 1~10 keeps ten chips predictable).
            Map.entry("ai.chat.pinned-grounding.review-max", new int[]{1, 10}),
            Map.entry("ai.chat.pinned-grounding.failure-max", new int[]{1, 10}),
            // : composer height drag ceiling. Floor 3 = the factory
            // default rows (a cap below it would squeeze the composer under
            // its own default height); 20 keeps one box under half a screen.
            Map.entry("ai.chat.composer-max-rows", new int[]{3, 20}),
            // cross-message image replay cap — how many
            // earlier attached images one turn may re-read by message anchor
            // (read side lands in the image replay path; 1~10 bounds the
            // vision-call cost of a single question).
            Map.entry("ai.chat.image-replay-cap", new int[]{1, 10}),
            // piece E: grace minutes a credential-screenshot frame may
            // age before the compensating sweep purges the booked originals
            // (the inline terminal-state delete is the primary path; 1 keeps
            // the window tight, 1440 = a day covers long retry storms).
            Map.entry("ai.chat.credential-grace-minutes", new int[]{1, 1440}));

    /** Bool keys the group accepts only true/false for. */
    private static final Set<String> BOOL_KEYS = Set.of(
            "ai.chat.exec-enabled",
            // Knife 3a : dialog repair-proposal draft gate (V300, default on).
            "ai.chat.repair-draft.enabled",
            // stage 2: dialog case-write draft gate (V303, default on).
            "ai.chat.case-draft.enabled",
            // Rehearsal launch sub switch (V336, factory default off — the
            // production-change exemption registered in the seed comment).
            "ai.chat.rehearsal-run.enabled",
            // Paste-table conversion switch (V506, factory on): the composer
            // reader treats anything but false/0 as on, so the write side
            // keeps the value to a strict true/false.
            "ai.chat.paste-table-convert.enabled");

    /**
     * Double keys the group interval-guards: key → {lower, upper}. The
     * grounding relevance floor is a ratio — an out-of-range value would
     * either cite everything (negative floor) or nothing (floor above the
     * max achievable score), so the write side spells the interval out.
     */
    private static final Map<String, double[]> DOUBLE_RANGES = Map.of(
            "ai.chat.grounding.min-score", new double[]{0.0, 1.0});

    /** Prefix of the whole group (write-side entry check). */
    public static final String KEY_PREFIX = "ai.chat.";

    private AiChatKeyRanges() {
    }

    /** Interval for an ai.chat.* int key, or null when not interval-guarded. */
    public static int[] rangeOf(String key) {
        return INT_RANGES.get(key);
    }

    /** Interval for an ai.chat.* double key (ratios), or null when not guarded. */
    public static double[] doubleRangeOf(String key) {
        return DOUBLE_RANGES.get(key);
    }

    /** True for ai.chat.* bool switches (true/false only). */
    public static boolean isBoolKey(String key) {
        return key != null && BOOL_KEYS.contains(key);
    }

    /** True for any ai.chat.* group key (single write-side dispatch check). */
    public static boolean isChatKey(String key) {
        return key != null && key.startsWith(KEY_PREFIX);
    }
}
