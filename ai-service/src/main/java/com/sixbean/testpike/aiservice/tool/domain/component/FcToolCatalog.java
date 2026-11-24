package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import com.sixbean.testpike.aiservice.llm.domain.component.SysConfigInts;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * AP-5 deferred tool-catalog assembly ; adjudication
 * count the FC prompt keeps a small core set resident plus this file's built-in
 * {@code search_tools} meta def, while every other assembled candidate rides a
 * compact system-prompt catalog line and is materialized on demand (Hermes
 * posture: search → describe → call).
 * <p>Two invariants the split must never break: the routing index (dispatcher
 * authorization surface) keeps EVERY assembled candidate — deferral shrinks
 * the prompt, never the five-way intersection verdict computed upstream by
 * core; and a project with no executable candidate still yields the legacy
 * empty plan untouched (plain-completion semantics byte-identical).
 * <p>K8 hot keys, read fresh on every decision (FcRoundPolicy posture — no
 * caching, change takes effect next assembly):
 * {@code ai.tool.catalog-threshold} (default 20; ≤0 disables the catalog —
 * trigger gate written per the adjudication's honest-account note that below
 * ~20 tools deferral costs more than it buys) and {@code ai.tool.catalog-core-tools}
 * (comma-separated names staying resident; default empty = dialog built-ins
 * only) and {@code ai.tool.catalog-expand-max} (default 8 tools materialized
 * per search).
 */
@Component
public class FcToolCatalog {

    /** FC name of the meta-tool (adjudication word: search_tools). */
    public static final String NAME = "search_tools";

    /** K8 key: executable count above which non-core tools go to the catalog. */
    public static final String KEY_THRESHOLD = "ai.tool.catalog-threshold";
    /** K8 key: comma-separated tool names that always stay resident. */
    public static final String KEY_CORE_TOOLS = "ai.tool.catalog-core-tools";
    /** K8 key: max tools materialized per search_tools call. */
    public static final String KEY_EXPAND_MAX = "ai.tool.catalog-expand-max";

    
    static final int DEFAULT_THRESHOLD = 20;
    static final int DEFAULT_EXPAND_MAX = 8;

    private static final String SEARCH_PARAMETERS_JSON = """
            {"type":"object","properties":{
              "query":{"type":"string",
                "description":"工具名片段或能力关键词（子串匹配工具目录条目，英文名片段命中率最高；查项目数据本身可走 apiAccess.catalog）"}}},"required":["query"]}""";

    private static final String SEARCH_DESCRIPTION =
            "检索完整工具目录，返回匹配工具的完整参数结构；"
                    + "当前会话没有的能力先调用本工具检索，再按返回的参数调用对应工具。";

    private final CoreGateway coreGateway;
    private final ObjectMapper mapper;

    public FcToolCatalog(CoreGateway coreGateway, ObjectMapper mapper) {
        this.coreGateway = coreGateway;
        this.mapper = mapper;
    }

    /** Routing predicate for the ReAct loop (built-in, never dispatcher-routed). */
    public static boolean isSearchCall(String toolName) {
        return NAME.equals(toolName);
    }

    /** Current threshold (>0 enables). */
    public int threshold() {
        return SysConfigInts.parseInt(coreGateway.getSysConfig(KEY_THRESHOLD), DEFAULT_THRESHOLD);
    }

    /** Names that stay resident even when the catalog is active. */
    public Set<String> coreToolNames() {
        String raw = coreGateway.getSysConfig(KEY_CORE_TOOLS);
        Set<String> names = new LinkedHashSet<>();
        if (raw != null) {
            for (String part : raw.split(",")) {
                String name = part.trim();
                if (!name.isEmpty()) {
                    names.add(name);
                }
            }
        }
        return Set.copyOf(names);
    }

    /** Trigger gate: enabled (threshold>0) and above it. */
    public boolean active(int executableCount) {
        int threshold = threshold();
        return threshold > 0 && executableCount > threshold;
    }

    /** Definition mounted whenever the catalog is active. */
    public ChatToolDef searchToolDef() {
        return new ChatToolDef(new ChatToolFunction(NAME, SEARCH_DESCRIPTION, readStaticParameters()));
    }

    /** One schema-ready def per candidate (null = fail-closed drop, shared reader). */
    public ChatToolDef toolDefOf(ToolFcAssemblyRemoteItem item) {
        JsonNode parameters = FcSchemaReader.read(mapper, item);
        return parameters == null ? null
                : new ChatToolDef(new ChatToolFunction(item.name(), null, parameters));
    }

    /**
     * Compact catalog index for the system prompt: one line per deferred tool
     * (name — schema top-level description when present), plus the pointer to
     * the meta-tool. Bounded by the deferred set itself, never the full tool
     * table — this is the whole point of deferral.
     */
    public String catalogNote(List<ToolFcAssemblyRemoteItem> deferred) {
        StringBuilder note = new StringBuilder("\n\n工具目录（以下工具需先检索后使用，用 ")
                .append(NAME).append(" 按关键词检索完整参数后再调用）：");
        for (ToolFcAssemblyRemoteItem item : deferred) {
            String desc = descriptionOf(item);
            note.append("\n- ").append(item.name());
            if (!desc.isEmpty()) {
                note.append(" — ").append(desc);
            }
        }
        return note.toString();
    }

    /**
     * Execute one search: case-insensitive substring match over tool names and
     * schema descriptions, capped at the expand key; matched tools come back
     * both as model-visible text (name + full schema) and as ready-to-mount
     * definitions for the loop. A blank query refuses readably; an unmatched
     * keyword is answered honestly (the search ran, its ledger is empty — no
     * expansion). Neither path ever throws (fail-soft posture shared with the
     * dispatcher).
     */
    public FcCatalogSearchOutcome execute(String argumentsJson,
                                          List<ToolFcAssemblyRemoteItem> deferred) {
        String query = parseQuery(argumentsJson);
        if (query == null) {
            return refused("search_tools 缺少 query 参数：请传入工具名或能力关键词。");
        }
        String needle = query.toLowerCase(Locale.ROOT);
        int cap = SysConfigInts.positiveInt(coreGateway.getSysConfig(KEY_EXPAND_MAX), DEFAULT_EXPAND_MAX);
        List<ToolFcAssemblyRemoteItem> matched = new ArrayList<>();
        for (ToolFcAssemblyRemoteItem item : deferred) {
            if (item.name().toLowerCase(Locale.ROOT).contains(needle)
                    || descriptionOf(item).toLowerCase(Locale.ROOT).contains(needle)) {
                matched.add(item);
                if (matched.size() >= cap) {
                    break;
                }
            }
        }
        if (matched.isEmpty()) {
            // The search itself executed — an empty answer is not a failure.
            // Reporting it as a refusal rendered every catalog miss as
            // "tool not executed (rejected or failed)" on the dialog trace,
            // which users (correctly) read as a malfunction. Honest shape:
            // success with the empty ledger + next-step guidance, including
            // the generic interface door for platform data itself.
            return new FcCatalogSearchOutcome("工具目录中没有匹配「" + query
                    + "」的工具：换关键词重试（工具名片段或英文能力词优先），"
                    + "或走 apiAccess.catalog 检索本项目接口目录后用 apiAccess.read 代读数据。",
                    true, List.of());
        }
        StringBuilder content = new StringBuilder();
        List<ChatToolDef> expanded = new ArrayList<>();
        for (ToolFcAssemblyRemoteItem item : matched) {
            ChatToolDef def = toolDefOf(item);
            if (def == null) {
                continue;   // fail-closed (already journaled by the shared reader)
            }
            if (content.length() > 0) {
                content.append('\n');
            }
            content.append(item.name()).append(" | ").append(item.inputSchemaJson());
            expanded.add(def);
        }
        return new FcCatalogSearchOutcome(content.toString(), true, List.copyOf(expanded));
    }

    private FcCatalogSearchOutcome refused(String reason) {
        return new FcCatalogSearchOutcome(reason, false, List.of());
    }

    private String parseQuery(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) {
            return null;
        }
        try {
            String query = mapper.readTree(argumentsJson).path("query").asText(null);
            return query == null || query.isBlank() ? null : query.trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** Schema top-level description (empty when absent/unparseable). */
    private String descriptionOf(ToolFcAssemblyRemoteItem item) {
        JsonNode node = FcSchemaReader.read(mapper, item);
        return node == null ? "" : Objects.requireNonNullElse(node.path("description").asText(""), "");
    }

    private JsonNode readStaticParameters() {
        try {
            return mapper.readTree(SEARCH_PARAMETERS_JSON);
        } catch (Exception e) {
            throw new IllegalStateException("search_tools static schema must parse", e);
        }
    }
}
