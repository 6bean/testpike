package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigBooleans;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * WE batch S5 : folds the CONFIRMED rows of the page element
 * library into the stage-2 (steps-gen) prompt so the model references what
 * the team already verified instead of inventing a fresh — possibly worse —
 * locator for the same element.
 * <p>Chain = K8 gate ai.gen.ledger-summary.enabled (factory ON: an absent row
 * keeps the summary as the default stage-2 link, only an explicit 0/false
 * restores the legacy bytes) → row cap from ai.gen.ledger-summary.max-rows
 * (factory 60, clamp 1..500 mirrored server-side by the core prompt-summary
 * door) → character budget ai.gen.ledger-summary.max-chars (factory 1800;
 * over budget drops whole rows and appends one honest truncation line —
 * never a half row, never silent).
 * <p>fail-soft contract (PageInspectBridge/DdlDictBridge same base path):
 * any read failure = empty segment + WARN, the generation chain never stops.
 * <p>Discipline: computed once per task before the deliberation ring and
 * reused by every round (AP-6 stable bytes, inspectSegment discipline) —
 * the ordering tie-break (confidence DESC, id ASC) lives on the core read
 * side so the same ledger always renders the same bytes (prompt-cache
 * economy, prefix discipline).
 */
@Component
public class WebElementLedgerSummaryBridge {

    private static final Logger log = LoggerFactory.getLogger(WebElementLedgerSummaryBridge.class);

    /** K8 hot gate: an absent row keeps the summary as the default stage-2
     * link (factory ON); only an explicit 0/false restores the legacy bytes. */
    public static final String KEY_ENABLED = "ai.gen.ledger-summary.enabled";
    /** Row cap (shares the core door's 1..500 clamp — the intersection wins). */
    public static final String KEY_MAX_ROWS = "ai.gen.ledger-summary.max-rows";
    /** Row-body character budget: over budget drops whole rows and appends
     * one honest truncation line — never a half row, never silent. */
    public static final String KEY_MAX_CHARS = "ai.gen.ledger-summary.max-chars";

    public static final int DEFAULT_MAX_ROWS = 60;
    public static final int DEFAULT_MAX_CHARS = 1800;

    /** Segment title anchor (assertion surface, shared by template/built-in paths). */
    public static final String SEGMENT_ANCHOR = "本项目页面元素库已确认元素：";

    private static final String REFERENCE_INSTRUCTION =
            "引用优先规则：动作 target 命中上述元素时必须写引用形态 target 为 {{ELEM:元素名}}"
                    + "（执行期由平台按元素库锚序解析，改版修一处全引用方受益）；"
                    + "仅当清单确无该元素才允许自造内联定位符，自造锚会作为待确认候选入册。\n";

    private final CoreGateway coreGateway;
    private final ObjectMapper objectMapper;

    public WebElementLedgerSummaryBridge(CoreGateway coreGateway, ObjectMapper objectMapper) {
        this.coreGateway = coreGateway;
        this.objectMapper = objectMapper;
    }

    /**
     * Assemble the summary segment (whole chain fail-soft: gate off / empty
     * ledger / door failure = "" and the generation prompt keeps its bytes).
     *
     * @return segment text (title anchor + rows + reference-first line);
     *         gate off or no confirmed elements = ""
     */
    public String summarySegment(long projectId, long taskId) {
        if (!SysConfigBooleans.parse(coreGateway.getSysConfig(KEY_ENABLED), true)) {
            return "";
        }
        int maxRows = intConfig(KEY_MAX_ROWS, DEFAULT_MAX_ROWS, 1, 500);
        int maxChars = intConfig(KEY_MAX_CHARS, DEFAULT_MAX_CHARS, 200, 8000);
        List<Map<String, Object>> rows;
        try {
            rows = coreGateway.getLedgerPromptSummary(projectId, null, maxRows);
        } catch (Exception e) {
            log.warn("页面元素库摘要读取失败（fail-soft 不阻断生成）projectId={} taskId={}: {}",
                    projectId, taskId, e.getMessage());
            return "";
        }
        if (rows == null || rows.isEmpty()) {
            return "";
        }
        // Defensive row cap: the core door already clamps, but a drifted or
        // hand-rolled answer must never widen the prompt beyond the budget.
        rows = rows.subList(0, Math.min(rows.size(), maxRows));
        StringBuilder body = new StringBuilder();
        int rendered = 0;
        for (Map<String, Object> row : rows) {
            String line = renderRow(row);
            if (line == null) {
                continue;
            }
            if (body.length() + line.length() > maxChars) {
                body.append("-（其余 ").append(rows.size() - rendered).append(" 条超出注入预算，已截断）\n");
                break;
            }
            body.append(line);
            rendered++;
        }
        if (rendered == 0) {
            return "";
        }
        log.info("页面元素库摘要注入 projectId={} taskId={} 行数={}/{} 段长={}",
                projectId, taskId, rendered, rows.size(), body.length());
        return SEGMENT_ANCHOR + "\n" + body + REFERENCE_INSTRUCTION;
    }

    /** One human-readable row: - 「name」(page pageKey) primary anchor + alternates. */
    @SuppressWarnings("unchecked")
    private String renderRow(Map<String, Object> row) {
        Object rawName = row.get("name");
        Object rawAnchors = row.get("anchors");
        if (rawName == null || !(rawAnchors instanceof List)) {
            return null;
        }
        StringBuilder locators = new StringBuilder();
        for (Object item : (List<Object>) rawAnchors) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<String, Object> anchor = (Map<String, Object>) item;
            Object type = anchor.get("type");
            Object value = anchor.get("value");
            if (type == null || value == null) {
                continue;
            }
            locators.append(locators.isEmpty() ? "主锚 " : ", ").append(type).append('=').append(value);
            if (locators.length() > 120) {
                locators.append(" …");
                break;
            }
        }
        if (locators.isEmpty()) {
            return null;
        }
        Object pageKey = row.get("pageKey");
        return "- 「" + rawName + "」" + (pageKey == null ? "" : "（页 " + pageKey + "）")
                + locators + "\n";
    }

    /** K8 integer read: malformed or out-of-range falls back to the factory
     * value and names the key in a WARN (mirrors the core write-side range). */
    private int intConfig(String key, int fallback, int low, int high) {
        String raw = coreGateway.getSysConfig(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.strip());
            if (value < low || value > high) {
                log.warn("配置键 {} 值 {} 越界（{}~{}），按出厂 {} 回退（热更即生效）",
                        key, value, low, high, fallback);
                return fallback;
            }
            return value;
        } catch (NumberFormatException malformed) {
            log.warn("配置键 {} 值非法（当前 {}），按出厂 {} 回退", key, raw, fallback);
            return fallback;
        }
    }
}
