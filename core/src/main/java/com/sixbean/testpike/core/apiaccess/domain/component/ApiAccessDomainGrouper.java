package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.api.dto.ApiAccessEndpointView;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Groups catalog rows by domain prefix (the first two path segments) and renders the summary text.
 *
 * <p>Single source for two consumers (CC batch, R40): the zero-hit guidance row of the catalog
 * and the domain-level index the catalog delivers when a browse exceeds the row ceiling. Both
 * must count domains the same way — a second implementation here would drift apart silently and
 * the drill-down prefixes shown in one row would stop matching the grouping of the other.
 *
 * <p>A domain prefix doubles as a search keyword: the door's existing substring match on the
 * path makes "api/v1" or "api/cases" drill into that family with no new parameter.
 */
public final class ApiAccessDomainGrouper {

    /** One domain family with its entry count and a representative first entry. */
    public record DomainCount(String prefix, int count, String samplePath) {
    }

    private ApiAccessDomainGrouper() {
    }

    /**
     * Domain prefix of a path: first two non-empty segments, placeholders dropped (they carry no
     * family meaning); a short path keeps whatever segments it has.
     */
    public static String prefixOf(String pathPattern) {
        if (pathPattern == null || pathPattern.isBlank()) {
            return "其他";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : pathPattern.split("/")) {
            if (segment.isBlank() || segment.startsWith("{")) {
                continue;
            }
            segments.add(segment.toLowerCase(java.util.Locale.ROOT));
            if (segments.size() == 2) {
                break;
            }
        }
        return segments.isEmpty() ? "其他" : String.join("/", segments);
    }

    /**
     * Group rows by prefix, biggest families first (ties keep the first-seen order).
     *
     * @param rows already-filtered catalog rows to count; special guidance rows are skipped
     *             defensively so a caller cannot feed its own summary back in
     */
    public static List<DomainCount> group(List<ApiAccessEndpointView> rows) {
        Map<String, int[]> counts = new LinkedHashMap<>();
        Map<String, String> samples = new LinkedHashMap<>();
        for (ApiAccessEndpointView row : rows) {
            if (row.key() != null && row.key().startsWith("__")) {
                continue;
            }
            String prefix = prefixOf(row.pathPattern());
            counts.computeIfAbsent(prefix, k -> new int[1])[0]++;
            samples.putIfAbsent(prefix, row.pathPattern());
        }
        List<DomainCount> groups = new ArrayList<>();
        counts.forEach((prefix, count) ->
                groups.add(new DomainCount(prefix, count[0], samples.get(prefix))));
        groups.sort(Comparator.comparingInt(DomainCount::count).reversed());
        return groups;
    }

    /**
     * One-line domain summary: "prefix(count) / ..." with an honest sentence when nothing is
     * reachable (an empty summary would leave the reader without a verdict — R43 discipline).
     */
    public static String summaryText(List<DomainCount> groups) {
        if (groups.isEmpty()) {
            return "当前无可直读条目";
        }
        StringBuilder text = new StringBuilder("可达条目域计数：");
        for (int i = 0; i < groups.size(); i++) {
            DomainCount group = groups.get(i);
            if (i > 0) {
                text.append(" / ");
            }
            text.append(group.prefix()).append('(').append(group.count()).append(')');
        }
        return text.toString();
    }

    /**
 * Layered index rows: one row per domain family (prefix + count + a one-line hint naming a
 * sample entry), biggest families first. The reader drills a family by feeding its prefix
 * back as the catalog keyword — the existing substring match on the path, no new parameter.
 *
 * <p>When the family list itself exceeds {@code groupMax} (hot-updatable key
 * {@code ai.api-access.catalog-group-max}) the tail folds into one {@code __domains_rest__}
 * row that names how many domains and entries were folded: an index that silently omits
 * families would misrepresent the ledger just as a truncated segment does.
 */
    public static List<ApiAccessEndpointView> indexRows(List<DomainCount> groups, int groupMax) {
        int shown = Math.min(groups.size(), Math.max(groupMax, 1));
        List<ApiAccessEndpointView> rows = new ArrayList<>();
        for (int i = 0; i < shown; i++) {
            DomainCount group = groups.get(i);
            String text = "域 " + group.prefix() + " 共 " + group.count() + " 条可直读条目"
                    + "（示例 " + group.samplePath() + "）；把该域前缀「" + group.prefix()
                    + "」填入 keyword 重新检索，即可列出本域全部明细";
            rows.add(indexRow("__domain__" + group.prefix(), group.prefix(), text));
        }
        if (groups.size() > shown) {
            List<DomainCount> rest = groups.subList(shown, groups.size());
            int foldedEntries = rest.stream().mapToInt(DomainCount::count).sum();
            String text = "其余域 " + rest.size() + " 个共 " + foldedEntries
                    + " 条未逐行列出（域行数上限=系统配置页参数 ai.api-access.catalog-group-max，修改即时生效）；"
                    + "换更具体的关键词可直达其明细";
            rows.add(indexRow("__domains_rest__", "其余域", text));
        }
        return rows;
    }

    /**
     * One index row. Keys start with {@code __} so a caller feeding catalog rows back into
     * {@link #group} cannot count index rows as entries (see the guard there), and the read door
     * never resolves them — they are presentation, not endpoints.
     */
    private static ApiAccessEndpointView indexRow(String key, String prefix, String text) {
        return new ApiAccessEndpointView(key, "—", prefix, List.of(), false, false, false,
                text, text, null, List.of());
    }
}
