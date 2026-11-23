package com.sixbean.testpike.aiservice.gen.domain.component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mechanical rule-item extraction over the anchored original-text block set
 * WRONG, never against written MISSING — this component produces the item
 * ledger that the per-item semantic reconciliation runs against.
 *
 * <p>Two shapes are recognised mechanically:
 * <ul>
 * <li>pipe tables whose header carries rule column synonyms (field /
 * input type / required / constraint) — each data row becomes a
 * {@link RuleItemKind#TABLE_ROW} item;</li>
 * each becomes a {@link RuleItemKind#PROSE_CANDIDATE} booked for
 * human adjudication, never auto-judged.</li>
 * </ul>
 *
 * <p>Fail-soft by design: malformed tables, tables without any rule header
 * and blank blocks yield no items instead of throwing — extraction must not
 * break a generation run. Pure function: no DB, no LLM, no config reads;
 * the reconciliation wiring and the hot-switch key arrive with the next
 * segment. Repository-wide single source for this parsing (R40).
 */
public class RuleItemExtractor {

    /** Header cell synonyms per rule column, matched by substring. */
    private static final List<String> FIELD_HEADERS = List.of("字段", "名称", "输入项", "项目", "栏位");
    private static final List<String> TYPE_HEADERS = List.of("输入类型", "类型", "控件");
    private static final List<String> REQUIRED_HEADERS = List.of("必填");
    private static final List<String> CONSTRAINT_HEADERS =
            List.of("限制", "校验", "规则", "约束", "取值");

    /** Wording that marks a prose line as a rule candidate (conservative set). */
    private static final List<String> PROSE_TRIGGERS = List.of(
            "必须", "不得", "禁止", "仅允许", "仅可", "不超过", "不少于", "至少", "最多",
            "上限", "下限", "默认值");

    /** Column index per rule kind for one detected table (absent kinds stay unset). */
    private record RuleColumns(Map<String, Integer> indexByKind) {
        Integer field() {
            return indexByKind.get("field");
        }

        Integer inputType() {
            return indexByKind.get("inputType");
        }

        Integer required() {
            return indexByKind.get("required");
        }

        Integer constraint() {
            return indexByKind.get("constraint");
        }
    }

    /**
     * Extracts rule items from every block, keeping per-block attribution.
     * A null list or a blank block contributes nothing (fail-soft).
     */
    public List<RuleItem> extract(List<RuleSourceBlock> blocks) {
        List<RuleItem> items = new ArrayList<>();
        if (blocks == null) {
            return items;
        }
        for (RuleSourceBlock block : blocks) {
            if (block == null || block.text() == null || block.text().isBlank()) {
                continue;
            }
            extractFromBlock(block, items);
        }
        return items;
    }

    private void extractFromBlock(RuleSourceBlock block, List<RuleItem> out) {
        String[] lines = block.text().split("\n");
        int i = 0;
        while (i < lines.length) {
            String trimmed = lines[i].trim();
            if (isTableRow(trimmed)) {
                i = consumeTable(lines, i, block.chunkId(), out);
            } else {
                if (!trimmed.isEmpty() && containsAny(trimmed, PROSE_TRIGGERS)) {
                    out.add(new RuleItem(RuleItemKind.PROSE_CANDIDATE, block.chunkId(),
                            null, null, null, null, trimmed));
                }
                i++;
            }
        }
    }

    /** Parses one contiguous pipe-table run starting at {@code start}; returns the next line index. */
    private int consumeTable(String[] lines, int start, long chunkId, List<RuleItem> out) {
        List<List<String>> rows = new ArrayList<>();
        int i = start;
        while (i < lines.length && isTableRow(lines[i].trim())) {
            rows.add(splitCells(lines[i].trim()));
            i++;
        }
        if (rows.isEmpty()) {
            return i;
        }
        RuleColumns columns = matchHeader(rows.get(0));
        if (columns == null) {
            // Not a rule table (e.g. an order/status sample) — no items, no prose either.
            return i;
        }
        for (int r = 1; r < rows.size(); r++) {
            if (isSeparatorRow(rows.get(r))) {
                continue;
            }
            RuleItem item = tableItem(chunkId, rows.get(r), columns);
            if (item != null) {
                out.add(item);
            }
        }
        return i;
    }

    /** Maps header cells to rule columns; null when no rule column is present at all. */
    private RuleColumns matchHeader(List<String> headerCells) {
        Map<String, Integer> indexByKind = new HashMap<>();
        for (int c = 0; c < headerCells.size(); c++) {
            String cell = headerCells.get(c);
            if (cell.isEmpty()) {
                continue;
            }
            if (!indexByKind.containsKey("field") && containsAny(cell, FIELD_HEADERS)) {
                indexByKind.put("field", c);
            } else if (!indexByKind.containsKey("inputType") && containsAny(cell, TYPE_HEADERS)) {
                indexByKind.put("inputType", c);
            } else if (!indexByKind.containsKey("required") && containsAny(cell, REQUIRED_HEADERS)) {
                indexByKind.put("required", c);
            } else if (!indexByKind.containsKey("constraint") && containsAny(cell, CONSTRAINT_HEADERS)) {
                indexByKind.put("constraint", c);
            }
        }
        return indexByKind.isEmpty() ? null : new RuleColumns(indexByKind);
    }

    private RuleItem tableItem(long chunkId, List<String> cells, RuleColumns columns) {
        String field = cellAt(cells, columns.field());
        if (field == null || field.isBlank()) {
            // A row without a field name carries no reconcilable rule.
            return null;
        }
        String sourceText = String.join(" | ", cells);
        return new RuleItem(RuleItemKind.TABLE_ROW, chunkId, field,
                cellAt(cells, columns.inputType()),
                cellAt(cells, columns.required()),
                cellAt(cells, columns.constraint()),
                sourceText);
    }

    private static String cellAt(List<String> cells, Integer index) {
        if (index == null || index >= cells.size()) {
            return null;
        }
        String value = cells.get(index);
        return value.isBlank() ? null : value;
    }

    /** Separator cells consist only of dashes with optional alignment colons. */
    private static boolean isSeparatorRow(List<String> cells) {
        if (cells.isEmpty()) {
            return false;
        }
        boolean sawDashCell = false;
        for (String cell : cells) {
            if (cell.isEmpty()) {
                continue;
            }
            if (!cell.matches(":?-{2,}:?")) {
                return false;
            }
            sawDashCell = true;
        }
        return sawDashCell;
    }

    private static boolean isTableRow(String trimmedLine) {
        return trimmedLine.startsWith("|") && trimmedLine.chars().filter(ch -> ch == '|').count() >= 2;
    }

    private static List<String> splitCells(String trimmedLine) {
        String body = trimmedLine;
        if (body.endsWith("|")) {
            body = body.substring(0, body.length() - 1);
        }
        if (body.startsWith("|")) {
            body = body.substring(1);
        }
        List<String> cells = new ArrayList<>();
        for (String raw : body.split("\\|", -1)) {
            cells.add(raw.trim());
        }
        return cells;
    }

    private static boolean containsAny(String text, List<String> needles) {
        for (String needle : needles) {
            if (text.contains(needle)) {
                return true;
            }
        }
        return false;
    }
}
