package com.sixbean.testpike.common.genkit;

import java.util.List;

/**
 * Outcome of one deterministic gap-fill pass: the rewritten script text and
 * the human-readable notes the process ledger records for every judgement
 * the machine made but could not finish alone.
 *
 * @param script rewritten action JSON (identity when nothing was repairable)
 * @param notes  ledger lines: refinements needed, honest drops, degradations
 */
public record ScriptGapFillResult(String script, List<String> notes) {
}
