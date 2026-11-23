package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Trimming sweeper for the AI process-event ledger {@code tp_ai_task_event}.
 * <p>Why this class exists: V460 seeded the two hot keys
 * {@code ai.stream.event-retention-days} and {@code ai.stream.max-events-per-task}
 * when the ledger table was created, but no component ever read them, so the
 * table was append-only without bound — a long-running extraction writes one row
 * per chunk and a fleet of runs grows it forever. This class is the single
 * consumer that turns both keys into enforced limits, and it is the reclaim
 * path paired with the write side (rows are created by the job-event writers in
 * ai-service and read by {@link TaskEventWindowReader}).
 * <p>Three reclaim faces, all idempotent and safe to re-run:
 * <ul>
 *   <li>TTL window — rows whose {@code created_at} is older than the freshly
 *       read retention days of their family are deleted (site-family rows age
 *       out on {@link #SITE_EVENT_RETENTION_DAYS_KEY}, every other kind on the
 *       global key; fresh rows of the same job survive: the predicate is the
 *       row's own timestamp, never a whole-job cut);</li>
 *   <li>per-run ceiling — inside each {@code (job_kind, job_ref, run_token)}
 *       group the newest rows up to the family ceiling are kept and the older
 *       surplus is evicted (site family: {@link #SITE_HISTORY_FRAME_CAP_KEY},
 *       everything else: the global key), so a stuck retry loop cannot keep
 *       the tail drawer behind an ever-growing wall of milestones;</li>
 *   <li>terminal purge — when the owning business row is deleted, its whole
 *       event face is purged by {@code (job_kind, job_ref)} so an orphan stream
 *       is never replayable under a reused id.</li>
 * </ul>
 * <p>Key posture: both limits are read fresh from the hot keys on every round
 * (no cached copy, no restart needed) and clamped into the seeded allowed
 * range — an out-of-range entry is narrowed to the range edge, an unreadable
 * entry falls back to the shipped default, and neither ever degrades into the
 * "0 means delete everything" reading.
 * <p>Transaction posture ({@code AuditRetentionCleaner} precedent): deletes run
 * as bounded batches ({@link #DELETE_BATCH_LIMIT} rows per statement, at most
 * {@link #MAX_BATCHES_PER_RUN} batches per face per round) so a first sweep over
 * a large backlog cannot turn into one long transaction; whatever is left is
 * picked up by the next round.
 */
@Component
public class AiTaskEventTrimSweeper {

    private static final Logger log = LoggerFactory.getLogger(AiTaskEventTrimSweeper.class);

    /** Hot key: days a process-event row stays visible (V460 seeds 7, allowed 1~90). */
    public static final String RETENTION_DAYS_KEY = "ai.stream.event-retention-days";

    /** Hot key: rows kept per job run (V460 seeds 2000, allowed 100~20000). */
    public static final String MAX_EVENTS_PER_TASK_KEY = "ai.stream.max-events-per-task";

    static final int RETENTION_DAYS_DEFAULT = 7;
    static final int RETENTION_DAYS_MIN = 1;
    static final int RETENTION_DAYS_MAX = 90;
    static final int MAX_EVENTS_DEFAULT = 2000;
    static final int MAX_EVENTS_MIN = 100;
    static final int MAX_EVENTS_MAX = 20000;

    /**
     * Hot key: frames one whole-site survey run keeps in the ledger (V643
     * seeds 2000, allowed 200~20000). {@link #KIND_WEB_ELEMENT_SURVEY_SITE}
     * rows ride this ceiling instead of the global per-run one, because a
     * site run legitimately spans far more frames than a single interaction
     * stream and its replay window is a product promise (R67 revisit face).
     */
    public static final String SITE_HISTORY_FRAME_CAP_KEY = "element.survey-site.history-frame-cap";

    /**
     * Hot key: days a whole-site survey frame stays replayable (V643 seeds 90,
     * allowed 7~730) — same family scoping as {@link #SITE_HISTORY_FRAME_CAP_KEY}.
     */
    public static final String SITE_EVENT_RETENTION_DAYS_KEY = "element.survey-site.event-retention-days";

    static final int SITE_RETENTION_DEFAULT = 90;
    static final int SITE_RETENTION_MIN = 7;
    static final int SITE_RETENTION_MAX = 730;
    static final int SITE_CAP_DEFAULT = 2000;
    static final int SITE_CAP_MIN = 200;
    static final int SITE_CAP_MAX = 20000;

    /** Rows removed per statement — keeps each batch a short transaction. */
    private static final int DELETE_BATCH_LIMIT = 5_000;

    /** Batches per face per round; the residue is swept by the next round. */
    private static final int MAX_BATCHES_PER_RUN = 20;

    /**
     * Job kinds whose owning row is a document, so their event face has to be
     * purged when the document is deleted. The writers are the authority for
     * these tokens: {@code doc-extract} is recorded by the extraction pipeline,
     * {@code qa-gen} by the evaluation-question generator, both keyed by
     * document id in {@code job_ref}.
     */
    public static final String KIND_DOC_EXTRACT = "doc-extract";
    public static final String KIND_QA_GEN = "qa-gen";

    /**
     * Process-stream kind of the page-element survey (R62 first case): the
     * kickoff/step/naming/terminal frames of one survey ride this kind with
     * {@code job_ref} = the explore session id. The per-row ledger audit
     * ("element-ledger", keyed by element row id) is a separate stream —
     * session ids and element ids come from different tables and can collide
     * numerically, so the two value ranges must never mix (mixed streams let
     * terminal cleanups step on each other and replays bleed across jobs).
     * The reclaim pairing of the creation point (the survey dispatch door in
     * {@code WebElementInternalController}) is the terminal purge in
     * {@code ExploreServiceImpl.cancel}: deleting the session row purges
     * exactly (this kind, session id) via {@link #purgeJobEvents}.
     */
    public static final String KIND_WEB_ELEMENT_SURVEY = "web-element-survey";

    /**
 * Process-stream kind of a whole-site survey run (WU batch , R62
 * family): kickoff/step/terminal frames of one site deep-crawl ride this
 * kind with {@code job_ref} = the explore session id, kept separate from
 * the single-page survey kind so replays of the two dialects never bleed
 * into each other's tail window. The ceiling sweep partitions by
 * {@code (job_kind, job_ref, run_token)} generically, so this kind enters
 * tail-trim coverage the moment its first frame is written; the paired
 * reclaim stays {@link #purgeJobEvents} on the owning session's delete
 * path.
 */
    public static final String KIND_WEB_ELEMENT_SURVEY_SITE = "web-element-survey-site";

    /**
     * Process-stream kind of a plain autonomous exploration session. The writer
     * is the ai-service exploration loop (ExploreLoopKernel.PROCESS_JOB_KIND)
     * which keys the stream the same way as the survey tail: job_ref = run_token
     * = the explore session id. Read back through the shared window door
     * (ExploreInternalController process-events) and reclaimed with the owning
     * row by the delete path in ExploreServiceImpl.cancel — creation and purge
     * stay paired so a deleted session never leaves a replayable orphan tail.
     */
    public static final String KIND_EXPLORE = "explore";

    /**
     * Process-stream kind of a batch execution run (R62 B-face): the kickoff
     * frame written by the start pipeline, the dispatch frame recorded when a
     * claimed task moves its batch to running, the per-case terminal frames
     * from the result callback and the sealed frame from the finalize shared
     * segment all ride this kind with {@code job_ref} = {@code run_token} =
     * the batch id. Batch rows are long-lived, so the paired reclaim path is
     * the TTL window sweep rather than a per-row purge - every written frame
     * leaves the ledger on its own after the retention window closes.
     */
    public static final String KIND_BATCH_EXEC = "batch-exec";

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;

    public AiTaskEventTrimSweeper(JdbcTemplate jdbcTemplate, ConfigService configService) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
    }

    /**
     * Full round used by the daily scheduler: TTL first, then the per-run
     * ceilings, plus the occupancy reading that makes the ledger size auditable
     * (a reclaim path without a number is not observability).
     */
    public void trimAll() {
        long expired = trimExpired();
        long overflow = trimOverflow();
        if (expired + overflow == 0) {
            return;
        }
        Long remaining = jdbcTemplate.queryForObject("SELECT count(*) FROM tp_ai_task_event", Long.class);
        log.info("过程事件台账裁剪：TTL 窗口删 {} 条、上限驱逐删 {} 条，剩余 {} 条",
                expired, overflow, remaining == null ? -1 : remaining);
    }

    /**
     * Retention window in days, read fresh from the hot key and clamped into
     * the seeded range 1~90.
     */
    public int resolveRetentionDays() {
        return resolveClamped(RETENTION_DAYS_KEY, RETENTION_DAYS_DEFAULT, RETENTION_DAYS_MIN, RETENTION_DAYS_MAX);
    }

    /**
     * Per-run row ceiling for the global family (every kind except the site
     * survey), read fresh from the hot key and clamped into 100~20000.
     */
    public int resolveMaxEventsPerTask() {
        return resolveClamped(MAX_EVENTS_PER_TASK_KEY, MAX_EVENTS_DEFAULT, MAX_EVENTS_MIN, MAX_EVENTS_MAX);
    }

    /**
     * Site-family retention window in days, read fresh from
     * {@link #SITE_EVENT_RETENTION_DAYS_KEY} and clamped into 7~730.
     */
    public int resolveSiteRetentionDays() {
        return resolveClamped(SITE_EVENT_RETENTION_DAYS_KEY, SITE_RETENTION_DEFAULT,
                SITE_RETENTION_MIN, SITE_RETENTION_MAX);
    }

    /**
     * Site-family frame ceiling, read fresh from {@link #SITE_HISTORY_FRAME_CAP_KEY}
     * and clamped into 200~20000.
     */
    public int resolveSiteHistoryFrameCap() {
        return resolveClamped(SITE_HISTORY_FRAME_CAP_KEY, SITE_CAP_DEFAULT, SITE_CAP_MIN, SITE_CAP_MAX);
    }

    /**
     * Scope predicate shared by both faces: {@code true} selects only the site
     * family rows, {@code false} everything else (a NULL kind can never be a
     * site row, so {@code IS DISTINCT FROM} keeps such rows in the global face).
     * The leading space is deliberate: text blocks strip line-trailing blanks,
     * so the seam before this predicate would otherwise glue keywords together.
     */
    private static String familyScope(boolean siteFamily) {
        return siteFamily ? " job_kind IS NOT DISTINCT FROM ?" : " job_kind IS DISTINCT FROM ?";
    }

    /**
     * TTL face: delete rows whose own {@code created_at} passed the retention
     * window of their family — site-family rows age out on the site key, every
     * other kind on the global key. The predicate never references a job, so a
     * fresh run of a job that also has expired rows keeps its live tail intact.
     *
     * @return rows removed in this round
     */
    public int trimExpired() {
        return trimExpiredFamily(true, resolveSiteRetentionDays())
                + trimExpiredFamily(false, resolveRetentionDays());
    }

    private int trimExpiredFamily(boolean siteFamily, int days) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = jdbcTemplate.update("""
                    DELETE FROM tp_ai_task_event
                     WHERE id IN (SELECT id FROM tp_ai_task_event
                                   WHERE created_at < now() - make_interval(days => ?)
                                     AND """ + familyScope(siteFamily) + """
                                   
                                   ORDER BY id LIMIT ?)""", days, KIND_WEB_ELEMENT_SURVEY_SITE, DELETE_BATCH_LIMIT);
            total += deleted;
            if (deleted < DELETE_BATCH_LIMIT) {
                return total;
            }
        }
        log.warn("过程事件台账 TTL 裁剪本轮封顶（{} 批 x {} 条，站点族={}），剩余超期行下轮续清",
                MAX_BATCHES_PER_RUN, DELETE_BATCH_LIMIT, siteFamily);
        return total;
    }

    /**
     * Ceiling face: evict the oldest surplus of every run that passed the
     * ceiling of its family. Grouping is per {@code (job_kind, job_ref, run_token)}
     * — the same key triple the read side windows on — so a busy job never
     * trims another job's tail and a run below its family ceiling is never
     * touched.
     *
     * @return rows removed in this round
     */
    public int trimOverflow() {
        return trimOverflowFamily(true, resolveSiteHistoryFrameCap())
                + trimOverflowFamily(false, resolveMaxEventsPerTask());
    }

    private int trimOverflowFamily(boolean siteFamily, int cap) {
        int total = 0;
        for (int batch = 0; batch < MAX_BATCHES_PER_RUN; batch++) {
            int deleted = jdbcTemplate.update("""
                    WITH ranked AS (
                        SELECT id, ROW_NUMBER() OVER (PARTITION BY job_kind, job_ref, run_token
                                                       ORDER BY id DESC) AS rn
                        FROM tp_ai_task_event
                        WHERE """ + familyScope(siteFamily) + """
                          
                    ), victims AS (
                        SELECT id FROM ranked WHERE rn > ? ORDER BY id LIMIT ?
                    )
                    DELETE FROM tp_ai_task_event
                     WHERE id IN (SELECT id FROM victims)""",
                    KIND_WEB_ELEMENT_SURVEY_SITE, cap, DELETE_BATCH_LIMIT);
            total += deleted;
            if (deleted < DELETE_BATCH_LIMIT) {
                return total;
            }
        }
        log.warn("过程事件台账上限驱逐本轮封顶（{} 批 x {} 条，站点族={}），剩余超限行下轮续清",
                MAX_BATCHES_PER_RUN, DELETE_BATCH_LIMIT, siteFamily);
        return total;
    }

    /**
     * Terminal face: drop one job's whole event face. Called by the owner row's
     * delete path; a job with no rows left is a no-op returning 0, so a retried
     * delete never errors.
     *
     * @param jobKind registered job kind of the owning job
     * @param jobRef  owning business identity (document id for the doc-bound kinds)
     * @return rows removed, 0 when the job had no events
     */
    public int purgeJobEvents(String jobKind, String jobRef) {
        if (jobKind == null || jobKind.isBlank() || jobRef == null || jobRef.isBlank()) {
            log.warn("过程事件终态清理被跳过：jobKind/jobRef 为空（kind={} ref={}）", jobKind, jobRef);
            return 0;
        }
        return jdbcTemplate.update("DELETE FROM tp_ai_task_event WHERE job_kind = ? AND job_ref = ?",
                jobKind, jobRef);
    }

    /**
     * Hot key -> clamped integer: unparseable or blank falls back to the shipped
     * default, anything else is narrowed into [min, max]. A mistyped "0" reads
     * as the range floor, never as "sweep everything".
     */
    private int resolveClamped(String key, int fallback, int min, int max) {
        String raw = configService.getValue(key, String.valueOf(fallback));
        int parsed;
        try {
            parsed = Integer.parseInt(raw == null ? "" : raw.trim());
        } catch (NumberFormatException unreadable) {
            log.warn("过程事件裁剪配置不可解析，按出厂缺省 {}：key={} raw={}", fallback, key, raw);
            return fallback;
        }
        return Math.min(Math.max(parsed, min), max);
    }
}
