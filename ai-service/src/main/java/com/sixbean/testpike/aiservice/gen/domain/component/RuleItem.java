package com.sixbean.testpike.aiservice.gen.domain.component;

/**
 * A rule item mechanically extracted from the anchored original-text block
 * set . For {@link RuleItemKind#TABLE_ROW} the four columns carry
 * the field-by-field values of the source row (a column missing from the
 * header stays {@code null}, never an invented value); for
 * {@link RuleItemKind#PROSE_CANDIDATE} only {@code sourceText} is filled —
 * prose candidates await human adjudication.
 *
 * @param kind extraction shape of this item
 * @param chunkId source block attribution for the fidelity ledger
 * @param field field name column (table rows only)
 * @param inputType input type column (table rows only)
 * @param required required flag column (table rows only)
 * @param constraint limit/validation column (table rows only)
 * @param sourceText raw source line the item was extracted from
 */
public record RuleItem(
        RuleItemKind kind,
        long chunkId,
        String field,
        String inputType,
        String required,
        String constraint,
        String sourceText) {

    /** Machine-readable text used as the reconciliation criterion item. */
    public String criterionText() {
        if (kind == RuleItemKind.PROSE_CANDIDATE) {
            return sourceText;
        }
        StringBuilder sb = new StringBuilder();
        appendIfPresent(sb, field);
        appendIfPresent(sb, inputType);
        appendIfPresent(sb, required);
        appendIfPresent(sb, constraint);
        return sb.toString();
    }

    private static void appendIfPresent(StringBuilder sb, String part) {
        if (part != null && !part.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(part);
        }
    }
}
