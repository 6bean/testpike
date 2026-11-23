package com.sixbean.testpike.core.events.domain.component;

import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Process-event writer door (single Java side entry for the shared
 * tp_ai_task_event ledger, mirroring the ai-service writer path).
 *
 * <p>Every "run test now" style flow records its steps here so the tail
 * drawer can replay them with cursor paging through TaskEventWindowReader.
 * Summary is clipped to the ledger column width; detail is plain text and
 * carries at most the hot budget {@code ui.tail.detail-max} - an over-budget
 * write ends with the honest total-size marker (the full-text read door
 * serves the rest, nothing disappears silently). Secret material must NEVER
 * enter detail in plaintext: callers pass already-masked content and seal
 * any later-revealable value through the dedicated sealed column.
 */
@Component
public class TaskEventWriter {

    /** Ledger column budget for the one-line summary. */
    public static final int SUMMARY_MAX = 190;
    /** Hot key for the detail display budget (factory default below). */
    public static final String DETAIL_MAX_KEY = "ui.tail.detail-max";
    public static final int DETAIL_MAX_DEFAULT = 16384;

    public static final String LEVEL_STEP = "step";
    public static final String LEVEL_ERROR = "error";

    private final JdbcTemplate jdbc;
    private final ConfigService configService;

    public TaskEventWriter(JdbcTemplate jdbc, ConfigService configService) {
        this.jdbc = jdbc;
        this.configService = configService;
    }

    /** Fresh run token: one logical test run = one token = one event tail. */
    public String newRunToken() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public void step(String jobKind, String jobRef, String runToken, String summary, String detail) {
        append(jobKind, jobRef, runToken, LEVEL_STEP, summary, detail, null, null);
    }

    /** Step with the ciphertext sidecar of any secret values echoed masked in detail. */
    public void step(String jobKind, String jobRef, String runToken, String summary,
                     String detail, String sealed) {
        append(jobKind, jobRef, runToken, LEVEL_STEP, summary, detail, sealed, null);
    }

    /**
     * Step carrying, beside the clipped preview, the full unclipped trail text
     * (detail_full). The over-budget marker in the preview promises a full
     * read; this overload keeps that promise true - without the extra copy an
     * over-budget write would be a silent hard cut dressed up as a pointer.
     */
    public void step(String jobKind, String jobRef, String runToken, String summary,
                     String detail, String sealed, String fullDetail) {
        append(jobKind, jobRef, runToken, LEVEL_STEP, summary, detail, sealed, fullDetail);
    }

    public void error(String jobKind, String jobRef, String runToken, String summary, String detail) {
        append(jobKind, jobRef, runToken, LEVEL_ERROR, summary, detail, null, null);
    }

    /** Current detail display budget; an unreadable knob row falls back to the default. */
    public int detailBudget() {
        try {
            return Integer.parseInt(configService
                    .getValue(DETAIL_MAX_KEY, String.valueOf(DETAIL_MAX_DEFAULT)).strip());
        } catch (RuntimeException drift) {
            return DETAIL_MAX_DEFAULT;
        }
    }

    private void append(String jobKind, String jobRef, String runToken,
                        String level, String summary, String detail, String sealed,
                        String fullDetail) {
        jdbc.update("INSERT INTO tp_ai_task_event (job_kind, job_ref, run_token, level, summary, detail, sealed, detail_full)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                jobKind, jobRef, runToken, level, clip(summary, SUMMARY_MAX), clipDetail(detail),
                sealed, fullDetail);
    }

    private static String clip(String value, int max) {
        String v = value == null ? "" : value;
        return v.length() <= max ? v : v.substring(0, max - 1) + "…";
    }

    private String clipDetail(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int budget = Math.max(1, detailBudget());
        return value.length() <= budget
                ? value
                : value.substring(0, budget) + "\n…（共 "
                        + value.getBytes(StandardCharsets.UTF_8).length
                        + " 字节，超出显示上限，可点「展开全文」）";
    }
}

