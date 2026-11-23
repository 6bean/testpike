package com.sixbean.testpike.core.ai.domain.service;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.aibatch.AiBatchItem;
import com.sixbean.testpike.common.aibatch.BatchItemNormalizer;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateInput;
import com.sixbean.testpike.core.ai.api.dto.AiBatchCreateResult;
import com.sixbean.testpike.core.ai.api.dto.AiBatchEstimate;
import com.sixbean.testpike.core.ai.api.dto.AiBatchInfo;
import com.sixbean.testpike.core.ai.api.dto.AiBatchItemInfo;
import com.sixbean.testpike.core.ai.api.dto.AiTaskCreateInput;
import com.sixbean.testpike.core.ai.domain.component.AiQueueNoticeAssembler;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI batch generation service implementation (tp_ai_batch + tp_ai_task.batch_id).
 * <p>Validation order: empty list, then exceeding the limit (config key ai.batch.max-count, default 500, valid range 1 to 500).
 * Hot update means ConfigCacheHolder synchronizes the cache on the write path, then validate each item for empty or over-length and indicate the Nth item in human-readable text; the cost estimation formula is:
 * The cost is the sum of the fixed prompt overhead plus the character count multiplied by tokens per character, then multiplied by the unit price, using ai.batch.* configuration items with default values as fallback.
 * Batch progress is real-time aggregation of subtask status, avoiding redundant status storage and double-write inconsistency.
 *
 */
@Service
public class AiBatchServiceImpl implements AiBatchService {

 /** Batch maximum key (single source of truth; write-side range validation uses the same constant in ConfigServiceImpl). */
    public static final String KEY_BATCH_MAX_COUNT = "ai.batch.max-count";
    /** Shipped-on switch: batch submit without a prior preview auto-enumerates scenarios and submits fully selected. */
    public static final String KEY_BATCH_AUTO_ENUMERATE = "ai.gen.batch.auto-enumerate.enabled";

 /** Batch maximum default value and valid range (missing or out-of-range values fall back to the default, and the write-side range definition is in ConfigServiceImpl). */
    public static final int DEFAULT_BATCH_MAX = 500;
    public static final int BATCH_MAX_LOWER = 1;
    public static final int BATCH_MAX_UPPER = 500;

    /** Single requirement length limit (consistent with single generation, no more than 5000). */
    private static final int MAX_REQ_LEN = 5000;

    /** Queue-waiting notice keys (V620 seed; missing or non-positive rows fall back to shipped defaults). */
    public static final String KEY_WINDOW_NOTICE_THRESHOLD = "ai.gen.window-priority.notice-backlog-threshold";
    public static final String KEY_WINDOW_NOTICE_SAMPLE_HOURS = "ai.gen.window-priority.estimate-sample-hours";
    public static final String KEY_WINDOW_NOTICE_CONCURRENCY = "ai.gen.window-priority.consumer-concurrency";
    /** Window-feed doors (V620/V621 seed): master switch defaults on, the project whitelist defaults empty = fail closed. */
    public static final String KEY_WINDOW_PRIORITY_ENABLED = "ai.gen.window-priority.enabled";
    public static final String KEY_WINDOW_WHITELIST = "ai.gen.window-priority.project-whitelist";
    static final int DEFAULT_WINDOW_NOTICE_THRESHOLD = 3;
    static final int DEFAULT_WINDOW_NOTICE_SAMPLE_HOURS = 24;
    static final int DEFAULT_WINDOW_NOTICE_CONCURRENCY = 4;

    /** Deliberation ring rounds governance row (same K8 key as ai-service DeliberationLoop.GOVERNANCE_KEY). */
    static final String KEY_DELIBERATION_ROUNDS = "ai.kernel.deliberation-rounds";

    /** Ring rounds fallback when the governance row is missing; valid range 1..3. */
    static final int DEFAULT_DELIBERATION_ROUNDS = 2;

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedJdbcTemplate;
    private final AiTaskService aiTaskService;
    private final SystemConfigReader configReader;
    private final ScenarioPreviewService scenarioPreviewService;

    /** Fixed prompt overhead in tokens. */
    @Value("${ai.batch.fixed-token-overhead:800}")
    private double fixedTokenOverhead;

    /** Estimated tokens per character. */
    @Value("${ai.batch.estimate-token-per-char:2.0}")
    private double tokenPerChar;

    /** Unit price in yuan per thousand tokens. */
    @Value("${ai.batch.price-per-1k-token:0.01}")
    private double pricePer1kToken;

    public AiBatchServiceImpl(JdbcTemplate jdbcTemplate, NamedParameterJdbcTemplate namedJdbcTemplate,
                              AiTaskService aiTaskService, SystemConfigReader configReader,
                              ScenarioPreviewService scenarioPreviewService) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedJdbcTemplate = namedJdbcTemplate;
        this.aiTaskService = aiTaskService;
        this.configReader = configReader;
        this.scenarioPreviewService = scenarioPreviewService;
    }

    /** Current batch maximum supports hot updates by reading the cache so changes take effect immediately; missing, non-numeric, or out-of-range values fall back to the default 500 as a double safeguard. */
    @Override
    public int currentMaxCount() {
        int configured = configReader.getInt(KEY_BATCH_MAX_COUNT, DEFAULT_BATCH_MAX);
        return configured < BATCH_MAX_LOWER || configured > BATCH_MAX_UPPER ? DEFAULT_BATCH_MAX : configured;
    }

 /**
     * Batch default full-enumeration switch: on means a submit without a prior
     * preview first runs the stage0 scenario enumeration and goes out fully
     * selected, so one requirement point yields several cases by default.
     * Missing rows fall back to the shipped default of on; only an explicit
     * off turns the whole-row legacy shape back on.
     */
    @Override
    public boolean currentAutoEnumerate() {
        return configReader.getBoolean(KEY_BATCH_AUTO_ENUMERATE, true);
    }

    @Override
    public AiBatchEstimate estimate(long projectId, List<String> requirements) {
        return estimate(projectId, requirements, null);
    }

    @Override
    public AiBatchEstimate estimate(long projectId, List<String> requirements, Long previewBatchId) {
        validate(requirements);
        List<Integer> scenarioCounts = previewBatchId == null
                ? List.of() : scenarioPreviewService.scenarioCountsByBatch(previewBatchId);
        long tokens = 0;
        int scenarioTotal = 0;
        for (int i = 0; i < requirements.size(); i++) {
            String r = requirements.get(i);
            long base = Math.round(fixedTokenOverhead + r.length() * tokenPerChar);
            if (scenarioCounts.isEmpty()) {
                tokens += base;
                continue;
            }
            // Previews are stored in submission order; a line without its own
            // row (short list) falls back to one unit, never to zero cost.
            int scenarios = i < scenarioCounts.size() ? scenarioCounts.get(i) : 0;
            scenarioTotal += scenarios;
            tokens += base * Math.max(1, scenarios);
        }
        Integer rounds = null;
        if (!scenarioCounts.isEmpty()) {
            // Section 10 item 1: the ring multiplies every estimation when
            // stage0 ran. Same K8 governance row as the ai-service
            // DeliberationLoop (single DB row; the key literal is repeated
            // because the modules share no constant artifact).
            rounds = Math.min(3, Math.max(1,
                    configReader.getInt(KEY_DELIBERATION_ROUNDS, DEFAULT_DELIBERATION_ROUNDS)));
            tokens *= rounds;
        }
        double cost = BigDecimal.valueOf(tokens / 1000.0 * pricePer1kToken)
                .setScale(4, RoundingMode.HALF_UP).doubleValue();
        return new AiBatchEstimate(requirements.size(), tokens, cost, currentMaxCount(),
                scenarioCounts.isEmpty() ? null : scenarioTotal, rounds);
    }

    @Override
    @Transactional
    public AiBatchCreateResult createBatch(AiBatchCreateInput input) {
        List<AiBatchItem> items = input.items();
        validate(BatchItemNormalizer.texts(items));
        assertRequirementLinks(input.projectId(), items);
        // Window-feed honor rule (three doors, server-side authority): the caller
        // declares, the hot switch can turn the whole channel off, and only
        // whitelisted projects may buy priority — an arbitrary user self-marking
        // the header stays in the regular queue (queue fairness guard).
        boolean windowFeed = input.windowFeedDeclared()
                && configReader.getBoolean(KEY_WINDOW_PRIORITY_ENABLED, true)
                && windowWhitelisted(configReader.getString(KEY_WINDOW_WHITELIST, ""), input.projectId());
        Long batchId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_ai_batch (project_id, operator_id, total, window_class)
                VALUES (?, ?, ?, ?) RETURNING id
                """, Long.class, input.projectId(), input.operatorId(), items.size(), windowFeed ? 1 : 0);
        List<Long> taskIds = new ArrayList<>(items.size());
        for (AiBatchItem item : items) {
            long taskId = aiTaskService.create(new AiTaskCreateInput(
                    input.projectId(), input.operatorId(), 1, item.text(), item.requirementId(), null, batchId));
            taskIds.add(taskId);
        }
        return new AiBatchCreateResult(batchId, taskIds, windowFeed);
    }

    /**
     * Pure whitelist rule for the window feed: comma-separated project ids.
     * Blank or malformed tokens are ignored (never fatal); an empty or missing
     * row denies every project so the channel fails closed until configured.
     */
    static boolean windowWhitelisted(String csv, long projectId) {
        if (csv == null || csv.isBlank()) {
            return false;
        }
        for (String token : csv.split(",")) {
            String trimmed = token.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                if (Long.parseLong(trimmed) == projectId) {
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // Malformed entries stay visible in the config row for manual cleanup.
            }
        }
        return false;
    }

    @Override
    public AiBatchInfo get(long batchId) {
        Object[] batch = jdbcTemplate.query("""
                        SELECT id, project_id, operator_id, total,
                               to_char(created_at, 'YYYY-MM-DD HH24:MI:SS')
                        FROM tp_ai_batch WHERE id = ?
                        """, (rs, rowNum) -> new Object[]{
                        rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getInt(4), rs.getString(5)},
                batchId).stream().findFirst().orElse(null);
        if (batch == null) {
            return null;
        }
        List<AiBatchItemInfo> items = jdbcTemplate.query("""
                SELECT id, status, requirement_text, case_id, error_code, error_msg
                FROM tp_ai_task WHERE batch_id = ? ORDER BY id
                """, (rs, rowNum) -> new AiBatchItemInfo(
                        rs.getLong("id"), rs.getInt("status"),
                        rs.getString("requirement_text"),
                        (Long) rs.getObject("case_id"),
                        rs.getString("error_code"), rs.getString("error_msg")),
                batchId);
        int pending = 0, running = 0, done = 0, failed = 0, cancelled = 0;
        for (AiBatchItemInfo item : items) {
            switch (item.status()) {
                case 1 -> pending++;
                case 2 -> running++;
                case 3 -> done++;
                case 4 -> failed++;
                // A stopped item is its own bucket: keeping it in the queued
                // bucket would make the progress line never settle.
                case 5 -> cancelled++;
                default -> { }
            }
        }
        return new AiBatchInfo((Long) batch[0], (Long) batch[1], (Long) batch[2], (Integer) batch[3],
                pending, running, done, failed, cancelled, (String) batch[4], items,
                buildQueueNotice(pending));
    }

    /**
     * Queue-waiting notice for batches that still hold pending items. Backlog
     * is the global pending+running task count (same authoritative signal the
     * window preflight reads); the estimate samples completed tasks inside
     * the configured window. Pure sentence rules live in
     * AiQueueNoticeAssembler — this is the only read path assembling it.
     */
    private String buildQueueNotice(int batchWaiting) {
        if (batchWaiting <= 0) {
            return null;
        }
        int threshold = positiveWindowNoticeConfig(KEY_WINDOW_NOTICE_THRESHOLD, DEFAULT_WINDOW_NOTICE_THRESHOLD);
        int sampleHours = positiveWindowNoticeConfig(KEY_WINDOW_NOTICE_SAMPLE_HOURS, DEFAULT_WINDOW_NOTICE_SAMPLE_HOURS);
        int concurrency = positiveWindowNoticeConfig(KEY_WINDOW_NOTICE_CONCURRENCY, DEFAULT_WINDOW_NOTICE_CONCURRENCY);
        Integer backlog = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_ai_task WHERE status IN (1, 2)", Integer.class);
        Long avgSeconds = jdbcTemplate.query(
                "SELECT round(avg(extract(epoch FROM (updated_at - created_at))))::bigint"
                        + " FROM tp_ai_task WHERE status = 3"
                        + " AND updated_at >= now() - make_interval(hours => ?)"
                        + " HAVING count(*) > 0",
                (rs, rowNum) -> rs.getLong(1), sampleHours)
                .stream().findFirst().orElse(null);
        return AiQueueNoticeAssembler.build(batchWaiting, backlog == null ? 0 : backlog,
                avgSeconds, concurrency, threshold, sampleHours);
    }

    /** Positive-value door for the notice keys: missing or non-positive rows fall back to shipped defaults. */
    private int positiveWindowNoticeConfig(String key, int shippedDefault) {
        int configured = configReader.getInt(key, shippedDefault);
        return configured <= 0 ? shippedDefault : configured;
    }

    /**
 * Latest batch of one project . The id lookup runs on
 * tp_ai_batch only, then reuses get(batchId) — one aggregation read path,
 * never a second bucket-count copy.
 */
    @Override
    public AiBatchInfo findLatestByProject(long projectId) {
        Long batchId = jdbcTemplate.query(
                "SELECT id FROM tp_ai_batch WHERE project_id = ? ORDER BY id DESC LIMIT 1",
                (rs, rowNum) -> rs.getLong(1), projectId)
                .stream().findFirst().orElse(null);
        return batchId == null ? null : get(batchId);
    }

  /** Batch input validation checks for an empty list, empty or over-length items, and the dynamic limit from the configuration key, returning a human-readable Chinese 400 error containing the current configured value. */
    private void validate(List<String> requirements) {
        int maxBatch = currentMaxCount();
        if (requirements == null || requirements.isEmpty()) {
            throw new ServiceException("请至少输入 1 条生成需求", BizErrorCode.VALIDATION_ERROR);
        }
        if (requirements.size() > maxBatch) {
            throw new ServiceException("单次上限 " + maxBatch + " 条，请分批提交", BizErrorCode.VALIDATION_ERROR);
        }
        for (int i = 0; i < requirements.size(); i++) {
            String requirement = requirements.get(i);
            if (requirement == null || requirement.isBlank()) {
                throw new ServiceException("第 " + (i + 1) + " 条需求不能为空", BizErrorCode.VALIDATION_ERROR);
            }
            if (requirement.length() > MAX_REQ_LEN) {
                throw new ServiceException("第 " + (i + 1) + " 条需求过长（≤" + MAX_REQ_LEN + " 字）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    /**
 * Requirement linkage gate : every entry that names a requirement point must
 * reference an existing row of the same project. Any unknown or cross-project id rejects the
 * whole submission before persistence with a human-readable message naming the entry number
 * and the offending id (one IN-query over the distinct ids, no per-item round trips).
 */
    private void assertRequirementLinks(long projectId, List<AiBatchItem> items) {
        List<Long> ids = new ArrayList<>();
        for (AiBatchItem item : items) {
            Long requirementId = item == null ? null : item.requirementId();
            if (requirementId != null && !ids.contains(requirementId)) {
                ids.add(requirementId);
            }
        }
        if (ids.isEmpty()) {
            return;
        }
        Map<Long, Long> ownerByRequirement = new HashMap<>();
        namedJdbcTemplate.query(
                "SELECT id, project_id FROM tp_requirement WHERE id IN (:ids)",
                Map.of("ids", ids),
                rs -> {
                    ownerByRequirement.put(rs.getLong(1), rs.getLong(2));
                });
        for (int i = 0; i < items.size(); i++) {
            Long requirementId = items.get(i) == null ? null : items.get(i).requirementId();
            if (requirementId == null) {
                continue;
            }
            Long ownerProject = ownerByRequirement.get(requirementId);
            if (ownerProject == null || ownerProject.longValue() != projectId) {
                throw new ServiceException("第 " + (i + 1) + " 条关联的需求点不存在或不属于当前项目（需求点 ID="
                        + requirementId + "），本次批量提交整体未受理", BizErrorCode.VALIDATION_ERROR);
            }
        }
    }
}
