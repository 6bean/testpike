package com.sixbean.testpike.core.retention.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.retention.api.dto.RetentionItem;
import com.sixbean.testpike.core.retention.domain.component.AuditRetentionCleaner;
import com.sixbean.testpike.core.retention.domain.service.RetentionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;


@Service
public class RetentionServiceImpl implements RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionServiceImpl.class);

    static final String KEY_VIDEO = "retention.video-days";
    static final String KEY_AUDIT = "retention.audit-days";
    static final String KEY_EXEC = "retention.exec-history-days";
    static final String KEY_SAMPLE = "retention.training-sample-days";
    /** Fifth class: finished non-golden perf runs past the retention window. */
    static final String KEY_PERF_RUN = "perf.run.retention-days";

 
    private static final Map<String, Integer> DEFAULT_DAYS = Map.of(
            KEY_VIDEO, 90, KEY_AUDIT, 365, KEY_EXEC, 180, KEY_SAMPLE, 730);

    
    private static final String TERMINAL_STATUSES = "3, 4, 5, 6";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final ArtifactStorage artifactStorage;
    private final AuditRetentionCleaner auditRetentionCleaner;
    private final PerfConfigReader perfConfigReader;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RetentionServiceImpl(JdbcTemplate jdbcTemplate, ConfigService configService,
                                ArtifactStorage artifactStorage, AuditRetentionCleaner auditRetentionCleaner,
                                PerfConfigReader perfConfigReader) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.artifactStorage = artifactStorage;
        this.auditRetentionCleaner = auditRetentionCleaner;
        this.perfConfigReader = perfConfigReader;
    }

    @Override
    @Audit(objectType = "retention", action = "preview", after = "#result")
    public List<RetentionItem> preview() {
        LocalDateTime now = LocalDateTime.now();
        List<RetentionItem> items = new ArrayList<>(5);
        int videoDays = days(KEY_VIDEO);
        int auditDays = days(KEY_AUDIT);
        int execDays = days(KEY_EXEC);
        int sampleDays = days(KEY_SAMPLE);
        int perfRunDays = perfConfigReader.runRetentionDays();
        items.add(new RetentionItem(KEY_VIDEO, "录屏产物", videoDays, cutoff(now, videoDays),
                countVideoArtifacts(now, videoDays)));
        items.add(new RetentionItem(KEY_AUDIT, "审计日志", auditDays, cutoff(now, auditDays),
                auditRetentionCleaner.previewExpiredPrefixCount(cutoff(now, auditDays))));
        items.add(new RetentionItem(KEY_EXEC, "执行历史", execDays, cutoff(now, execDays),
                countExpiredTasks(now, execDays)));
        items.add(new RetentionItem(KEY_SAMPLE, "训练样本", sampleDays, cutoff(now, sampleDays),
                countExpiredSamples(now, sampleDays)));
        items.add(new RetentionItem(KEY_PERF_RUN, "压测运行", perfRunDays, cutoff(now, perfRunDays),
                countExpiredPerfRuns(cutoff(now, perfRunDays))));
        return items;
    }

    @Override
    @Audit(objectType = "retention", action = "run", after = "#result")
    public List<RetentionItem> run() {
        LocalDateTime now = LocalDateTime.now();
        int videoDays = days(KEY_VIDEO);
        int auditDays = days(KEY_AUDIT);
        int execDays = days(KEY_EXEC);
        int sampleDays = days(KEY_SAMPLE);
        int perfRunDays = perfConfigReader.runRetentionDays();

        long videoRemoved = cleanVideoArtifacts(now, videoDays);
        long auditRemoved = auditRetentionCleaner.cleanExpiredPrefix(cutoff(now, auditDays));
        long execRemoved = cleanExpiredTasks(now, execDays);
        long sampleRemoved = cleanExpiredSamples(now, sampleDays);
        long perfRunRemoved = cleanExpiredPerfRuns(cutoff(now, perfRunDays));

        List<RetentionItem> items = List.of(
                new RetentionItem(KEY_VIDEO, "录屏产物", videoDays, cutoff(now, videoDays), videoRemoved),
                new RetentionItem(KEY_AUDIT, "审计日志", auditDays, cutoff(now, auditDays), auditRemoved),
                new RetentionItem(KEY_EXEC, "执行历史", execDays, cutoff(now, execDays), execRemoved),
                new RetentionItem(KEY_SAMPLE, "训练样本", sampleDays, cutoff(now, sampleDays), sampleRemoved),
                new RetentionItem(KEY_PERF_RUN, "压测运行", perfRunDays, cutoff(now, perfRunDays), perfRunRemoved));
        if (items.stream().anyMatch(i -> i.count() > 0)) {
            log.info("保留清理完成：录屏产物 {}、审计 {}、执行历史 {}、训练样本 {}、压测运行 {}",
                    videoRemoved, auditRemoved, execRemoved, sampleRemoved, perfRunRemoved);
        }
        return items;
    }


 
    private static final String HAS_ARTIFACT =
            " (video_key IS NOT NULL"
                    + " OR (screenshots_json IS NOT NULL AND screenshots_json::text NOT IN ('[]', 'null')))";

    
    private long countVideoArtifacts(LocalDateTime now, int days) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status IN (" + TERMINAL_STATUSES + ")"
                        + " AND created_at < ? AND" + HAS_ARTIFACT,
                Long.class, cutoff(now, days));
        return c == null ? 0 : c;
    }

    private long countExpiredTasks(LocalDateTime now, int days) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_exec_task WHERE status IN (" + TERMINAL_STATUSES
                        + ") AND created_at < ?", Long.class, cutoff(now, days));
        return c == null ? 0 : c;
    }

    private long countExpiredSamples(LocalDateTime now, int days) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_training_sample WHERE created_at < ?",
                Long.class, cutoff(now, days));
        return c == null ? 0 : c;
    }

    
    private long cleanVideoArtifacts(LocalDateTime now, int days) {
        LocalDateTime cut = cutoff(now, days);
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT id, video_key, screenshots_json::text AS shots FROM tp_exec_task"
                        + " WHERE status IN (" + TERMINAL_STATUSES + ")"
                        + " AND created_at < ? AND" + HAS_ARTIFACT, cut);
        for (Map<String, Object> row : rows) {
            Object vk = row.get("video_key");
            if (vk != null) {
                artifactStorage.deleteQuietly(String.valueOf(vk));
            }
            for (String key : parseObjectKeys((String) row.get("shots"))) {
                artifactStorage.deleteQuietly(key);
            }
            jdbcTemplate.update("UPDATE tp_exec_task SET video_key = NULL, screenshots_json = NULL,"
                    + " updated_at = now() WHERE id = ?", row.get("id"));
        }
        return rows.size();
    }

    private long cleanExpiredTasks(LocalDateTime now, int days) {
        return jdbcTemplate.update("DELETE FROM tp_exec_task WHERE status IN (" + TERMINAL_STATUSES
                + ") AND created_at < ?", cutoff(now, days));
    }

    private long cleanExpiredSamples(LocalDateTime now, int days) {
        return jdbcTemplate.update("DELETE FROM tp_training_sample WHERE created_at < ?",
                cutoff(now, days));
    }

    // ── perf-run window (same predicate for preview and run; golden exempt) ──

    /** Terminal perf-run statuses (2=success / 3=failed / 4=stopped); status 1=running
     *  never qualifies (ck_perf_run_status caps the domain at 1-4, verified on disk). */
    private static final String PERF_TERMINAL_STATUSES = "2, 3, 4";

    /** Expired non-golden perf runs (golden = permanent keep per the retention ruling). */
    private String perfRunExpiredPredicate() {
        return " WHERE status IN (" + PERF_TERMINAL_STATUSES + ")"
                + " AND mark IS DISTINCT FROM 'GOLDEN'"
                + " AND finished_at IS NOT NULL AND finished_at < ?";
    }

    private long countExpiredPerfRuns(LocalDateTime cut) {
        Long c = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_perf_run" + perfRunExpiredPredicate(),
                Long.class, cut);
        return c == null ? 0 : c;
    }

    /** Delete children first (tp_perf_metric / tp_perf_sample reference tp_perf_run
     *  without CASCADE, verified via pg_constraint) inside one statement batch so a
     *  crash between parent and child can never leave orphan rows pointing nowhere. */
    private long cleanExpiredPerfRuns(LocalDateTime cut) {
        String runSub = "SELECT id FROM tp_perf_run" + perfRunExpiredPredicate();
        jdbcTemplate.update("DELETE FROM tp_perf_sample WHERE run_id IN (" + runSub + ")", cut);
        jdbcTemplate.update("DELETE FROM tp_perf_metric WHERE run_id IN (" + runSub + ")", cut);
        return jdbcTemplate.update("DELETE FROM tp_perf_run" + perfRunExpiredPredicate(), cut);
    }

    
    private List<String> parseObjectKeys(String shotsJson) {
        if (shotsJson == null || shotsJson.isBlank()) {
            return List.of();
        }
        try {
            JsonNode arr = objectMapper.readTree(shotsJson);
            List<String> keys = new ArrayList<>();
            if (arr.isArray()) {
                arr.forEach(n -> {
                    if (n.isTextual() && !n.asText().isBlank()) {
                        keys.add(n.asText());
                    }
                });
            }
            return keys;
        } catch (Exception e) {
            log.warn("screenshots_json 解析失败（跳过对象删除）: {}", e.getMessage());
            return List.of();
        }
    }


    private LocalDateTime cutoff(LocalDateTime now, int days) {
        return now.minusDays(days);
    }

    private int days(String key) {
        String raw = configService.getValue(key, "");
        try {
            int v = Integer.parseInt(raw.trim());
            if (v > 0) {
                return v;
            }
        } catch (RuntimeException ignored) {
        }
        return DEFAULT_DAYS.get(key);
    }
}
