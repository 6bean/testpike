package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.perf.api.dto.PerfRunInsightRequest;
import com.sixbean.testpike.core.perf.domain.component.PerfAiSwitchGate;
import com.sixbean.testpike.core.perf.infrastructure.remote.client.AiServicePerfInsightClient;
import com.sixbean.testpike.core.webelement.domain.service.SiteSurveyAttributionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Batch-close attribution read-out for whole-site survey runs.
 * <p>When a survey run ends it leaves the honest numbers behind (page counters
 * on the run row, out-of-baseline write observations and network-level
 * failures on the page risk ledger) but no explanation of them. This sweep
 * assembles those numbers core-side into a facts block and hands them to the
 * existing insight door (the perfinsight leg — no second attribution engine),
 * then stores the returned paragraph on the run row so the report can show
 * why the run went the way it did without re-running the survey.
 * <p>Design rules pinned here: every count is computed from the ledger
 * before the model sees it (the model only explains, never re-derives); the
 * call carries no run anchor on purpose — the insight leg's event tail is
 * keyed by load-test run ids, and a survey id must never anchor into it;
 * the perfinsight switch gates the whole point, so one closed key costs zero
 * tokens; per-run failures are left un-stored for the next sweep (the write
 * is guarded by {@code attribution_summary IS NULL}, so replay is safe);
 * terminal runs with no risk fact are skipped, not summarized — an empty
 * risk book needs no explanation.
 */
@Component
public class SiteSurveyAttributionLedger implements SiteSurveyAttributionService {

    private static final Logger log = LoggerFactory.getLogger(SiteSurveyAttributionLedger.class);

    /** How far back a terminal run still qualifies for its first summary. */
    static final String KEY_LOOKBACK_HOURS = "survey.attribution-lookback-hours";
    /** Runs picked per sweep (bounded model spend per round). */
    static final String KEY_BATCH_SIZE = "survey.attribution-batch-size";
    /** Example lines per risk fact group inside the facts block. */
    static final String KEY_EXAMPLE_LINES = "survey.attribution-example-lines";
    /** Risk pages loaded per run when assembling the facts block. */
    static final String KEY_PAGE_CAP = "survey.attribution-risk-page-cap";

    private static final Map<String, String> STATUS_LABELS = Map.of(
            "completed", "完成", "partial", "部分完成", "failed", "失败", "cancelled", "已取消");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AiServicePerfInsightClient insightClient;
    private final PerfAiSwitchGate switchGate;
    private final ConfigService configService;

    public SiteSurveyAttributionLedger(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                       AiServicePerfInsightClient insightClient,
                                       PerfAiSwitchGate switchGate, ConfigService configService) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.insightClient = insightClient;
        this.switchGate = switchGate;
        this.configService = configService;
    }

    /**
     * One sweep round. Never throws: a refused or unreachable door leaves the
     * row untouched and the next round retries it.
     *
     * @return number of runs that received a summary in this round
     */
    public int sweepOnce() {
        if (!switchGate.isOpen(PerfAiSwitchGate.POINT_INSIGHT)) {
            return 0;
        }
        int lookbackHours = intConfig(KEY_LOOKBACK_HOURS, 72, 1);
        int batchSize = intConfig(KEY_BATCH_SIZE, 3, 1);
        int exampleLines = intConfig(KEY_EXAMPLE_LINES, 5, 1);
        int pageCap = intConfig(KEY_PAGE_CAP, 50, 5);
        List<Map<String, Object>> runs = jdbc.queryForList("""
                SELECT r.id, r.project_id, r.status, r.pages_total, r.pages_ok, r.pages_failed,
                       r.started_at, r.finished_at
                FROM tp_site_survey_run r
                WHERE r.status IN ('completed', 'partial', 'failed')
                  AND r.attribution_summary IS NULL
                  AND r.finished_at >= now() - make_interval(hours => :lookback)
                  AND EXISTS (SELECT 1 FROM tp_page_write_profile p
                              WHERE p.project_id = r.project_id
                                AND p.last_seen_at >= r.started_at
                                AND (p.snapshot_only
                                     OR jsonb_array_length(COALESCE(p.write_events, '[]'::jsonb)) > 0
                                     OR jsonb_array_length(COALESCE(p.net_failures, '[]'::jsonb)) > 0))
                ORDER BY r.finished_at DESC
                LIMIT :batch
                """, new MapSqlParameterSource()
                .addValue("lookback", lookbackHours)
                .addValue("batch", batchSize));
        int written = 0;
        for (Map<String, Object> run : runs) {
            long runId = ((Number) run.get("id")).longValue();
            try {
                String facts = factsBlock(run, exampleLines, pageCap);
                if (facts == null) {
                    continue;
                }
                Map<String, Object> reply = insightClient.explain(new PerfRunInsightRequest(
                        ((Number) run.get("project_id")).longValue(), facts, null));
                Object raw = reply == null ? null : reply.get("insight");
                String text = raw instanceof String value ? value.trim() : "";
                if (text.isBlank()) {
                    log.warn("survey attribution read-out empty for run {} (next round retries)", runId);
                    continue;
                }
                written += jdbc.update("UPDATE tp_site_survey_run SET attribution_summary = :s"
                                + " WHERE id = :id AND attribution_summary IS NULL",
                        new MapSqlParameterSource("s", text).addValue("id", runId));
            } catch (Exception perRun) {
                log.warn("survey attribution read-out skipped for run {} (next round retries): {}",
                        runId, perRun.getMessage());
            }
        }
        return written;
    }

    /** Facts block assembled from ledger numbers; null when the run carries no risk fact. */
    private String factsBlock(Map<String, Object> run, int exampleLines, int pageCap) {
        long runId = ((Number) run.get("id")).longValue();
        long projectId = ((Number) run.get("project_id")).longValue();
        LocalDateTime startedAt = toLocal(run.get("started_at"));
        List<Map<String, Object>> pages = jdbc.queryForList("""
                SELECT page_url, snapshot_only,
                       write_events::text AS write_events, net_failures::text AS net_failures
                FROM tp_page_write_profile
                WHERE project_id = :projectId AND last_seen_at >= :startedAt
                ORDER BY last_seen_at DESC
                LIMIT :cap
                """, new MapSqlParameterSource()
                .addValue("projectId", projectId)
                .addValue("startedAt", startedAt)
                .addValue("cap", pageCap));
        int writeCount = 0;
        int writePages = 0;
        int clientErrors = 0;
        int serverErrors = 0;
        int connectionFailures = 0;
        int failureCount = 0;
        int degradedPages = 0;
        StringBuilder writeExamples = new StringBuilder();
        StringBuilder failureExamples = new StringBuilder();
        for (Map<String, Object> page : pages) {
            JsonNode events = readArray((String) page.get("write_events"));
            JsonNode failures = readArray((String) page.get("net_failures"));
            if (!events.isEmpty()) {
                writePages++;
                writeCount += events.size();
                for (JsonNode event : events) {
                    if (writeExamples.isEmpty()) {
                        writeExamples.append("示例：")
                                .append(event.path("method").asText("?")).append(' ')
                                .append(event.path("url").asText("?"))
                                .append("（状态 ").append(event.path("status").asText("无"))
                                .append("，步骤 ").append(event.path("stepNo").asText("无")).append("）");
                    } else if (countLines(writeExamples) < exampleLines) {
                        writeExamples.append("；")
                                .append(event.path("method").asText("?")).append(' ')
                                .append(event.path("url").asText("?"));
                    }
                }
            }
            for (JsonNode failure : failures) {
                failureCount++;
                String statusText = failure.path("status").asText("");
                if (statusText.isEmpty()) {
                    connectionFailures++;
                } else {
                    int status = parseIntOrZero(statusText);
                    if (status >= 500) {
                        serverErrors++;
                    } else if (status >= 400) {
                        clientErrors++;
                    }
                }
                if (failureExamples.isEmpty()) {
                    failureExamples.append("示例：")
                            .append(failure.path("method").asText("?")).append(' ')
                            .append(failure.path("url").asText("?"))
                            .append(" → ").append(statusText.isEmpty() ? "连接失败" : statusText);
                } else if (countLines(failureExamples) < exampleLines) {
                    failureExamples.append("；")
                            .append(failure.path("method").asText("?")).append(' ')
                            .append(failure.path("url").asText("?"));
                }
            }
            if (Boolean.TRUE.equals(page.get("snapshot_only"))) {
                degradedPages++;
            }
        }
        if (writeCount == 0 && failureCount == 0 && degradedPages == 0) {
            return null;
        }
        StringBuilder block = new StringBuilder();
        block.append("整站探查运行 #").append(runId).append(" 归因事实（项目 #").append(projectId)
                .append("，结束状态 ").append(STATUS_LABELS.getOrDefault((String) run.get("status"), "未知"))
                .append("）：\n")
                .append("页面计数：访问 ").append(num(run.get("pages_total")))
                .append("，成功 ").append(num(run.get("pages_ok")))
                .append("，失败 ").append(num(run.get("pages_failed")))
                .append("。\n")
                .append("基线外写请求观测：").append(writePages).append(" 个页面共 ").append(writeCount)
                .append(" 条");
        if (!writeExamples.isEmpty()) {
            block.append("；").append(writeExamples);
        }
        block.append("。\n")
                .append("接口异常观测：4xx ").append(clientErrors)
                .append(" 条，5xx ").append(serverErrors)
                .append(" 条，连接失败或超时 ").append(connectionFailures).append(" 条");
        if (!failureExamples.isEmpty()) {
            block.append("；").append(failureExamples);
        }
        block.append("。\n")
                .append("因写风险被限定为只读快照的页面：").append(degradedPages).append(" 个。");
        return block.toString();
    }

    private JsonNode readArray(String raw) {
        try {
            JsonNode root = objectMapper.readTree(raw == null || raw.isBlank() ? "[]" : raw);
            return root.isArray() ? root : objectMapper.createArrayNode();
        } catch (Exception malformed) {
            log.warn("survey attribution risk payload unreadable (skipped as empty): {}",
                    malformed.getMessage());
            return objectMapper.createArrayNode();
        }
    }

    private int intConfig(String key, int fallback, int floor) {
        int parsed = parseIntOrZero(configService.getValue(key, String.valueOf(fallback)).trim());
        return Math.max(parsed, floor);
    }

    private static int countLines(StringBuilder examples) {
        int count = 1;
        for (int i = 0; i < examples.length(); i++) {
            if (examples.charAt(i) == '；') {
                count++;
            }
        }
        return count;
    }

    private static int parseIntOrZero(String raw) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (Exception malformed) {
            return 0;
        }
    }

    private static int num(Object value) {
        return value == null ? 0 : ((Number) value).intValue();
    }

    private static LocalDateTime toLocal(Object value) {
        if (value instanceof java.sql.Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        return value instanceof LocalDateTime direct ? direct : LocalDateTime.now().minusDays(30);
    }
}
