package com.sixbean.testpike.core.mock.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Paired reclaim path for the import-suggestion table (R57: every created face
 * ships its cleanup at the same place). Rows of tp_mock_spec_import_verdict are
 * advisory previews — once the retention window passes, nobody will ever read
 * them again, and a bulk-import fleet would otherwise grow the table forever.
 * <p>Posture copies the proven {@code AiTaskEventTrimSweeper} shape: the
 * retention limit is re-read from its hot key on every round (no restart, no
 * cached copy), a blank or garbage entry falls back to the shipped default and
 * is clamped into a sane range — never into the "0 means delete everything"
 * reading. Deletes run as bounded batches so a first sweep over a large
 * backlog cannot turn into one long transaction.
 * <p>Unconditional business component by design: the scheduler sidecar carries
 * the scheduling switch, this class must always exist in the context (11-12
 * lesson — a cleanup injected by owners cannot hide behind the cron flag).
 */
@Component
public class SpecImportVerdictRetentionCleaner {

    private static final Logger log = LoggerFactory.getLogger(SpecImportVerdictRetentionCleaner.class);

    /** Hot key: how many days a suggestion row stays on screen for later viewing. */
    public static final String KEY_RETENTION_DAYS = "mock.spec-import.verdict-retention-days";

    /** Shipped default of the retention window (matches the V663 seed). */
    public static final int DEFAULT_RETENTION_DAYS = 90;

    private static final int MIN_RETENTION_DAYS = 1;
    private static final int MAX_RETENTION_DAYS = 3650;
    private static final int DELETE_BATCH_LIMIT = 2_000;
    private static final int MAX_BATCHES_PER_RUN = 20;

    private final JdbcTemplate jdbc;
    private final ConfigService configService;

    public SpecImportVerdictRetentionCleaner(JdbcTemplate jdbc, ConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    /** Fresh retention window from the hot key, clamped; garbage falls back to default. */
    public int resolveRetentionDays() {
        String raw = configService.getValue(KEY_RETENTION_DAYS,
                String.valueOf(DEFAULT_RETENTION_DAYS));
        try {
            int parsed = Integer.parseInt(raw.trim());
            return Math.min(MAX_RETENTION_DAYS, Math.max(MIN_RETENTION_DAYS, parsed));
        } catch (Exception e) {
            return DEFAULT_RETENTION_DAYS;
        }
    }

    /** Delete suggestion rows older than the window; returns total removed rows. */
    public int trimExpired() {
        int days = resolveRetentionDays();
        int removed = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = jdbc.update("""
                    DELETE FROM tp_mock_spec_import_verdict
                     WHERE id IN (
                         SELECT id FROM tp_mock_spec_import_verdict
                          WHERE created_at < now() - make_interval(days => ?)
                          LIMIT ?
                     )
                    """, days, DELETE_BATCH_LIMIT);
            removed += deleted;
            if (deleted < DELETE_BATCH_LIMIT) {
                break;
            }
        }
        if (removed > 0) {
            log.info("导入智能建议到期清理: 保留窗口 {} 天, 本轮删除 {} 行", days, removed);
        }
        return removed;
    }
}
