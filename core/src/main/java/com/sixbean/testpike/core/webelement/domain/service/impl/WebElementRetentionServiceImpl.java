package com.sixbean.testpike.core.webelement.domain.service.impl;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.config.domain.component.SystemConfigReader;
import com.sixbean.testpike.core.webelement.api.dto.WebElementLedgerRetentionResult;
import com.sixbean.testpike.core.webelement.domain.component.WebElementLifecycle;
import com.sixbean.testpike.core.webelement.domain.service.WebElementRetentionService;
import com.sixbean.testpike.core.webelement.domain.service.WebElementService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Retention twin loop over tp_web_element . The shape
 * follows the O10 report-retention precedent (hot-read key, daily scheduler
 * with its own lock name) but consumes the ledger's own state machine door:
 * an aged candidate is retired through WebElementService.changeState, so the
 * automatic move leaves the same audit trail a human move does and a row
 * raced by a concurrent edit simply skips to the next round. Purge deletes
 * retired rows past the window; anchor stats leave via the FK cascade and
 * the per-row ledger events are removed in the same statement set so no
 * orphan events accumulate.
 */
@Service
public class WebElementRetentionServiceImpl implements WebElementRetentionService {

    static final String KEY_CANDIDATE_TTL_DAYS = "element.candidate-ttl-days";
    static final String KEY_RETENTION_DAYS = "element.retention-days";
    private static final int DEFAULT_DAYS = 90;

    private static final Logger log = LoggerFactory.getLogger(WebElementRetentionServiceImpl.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final SystemConfigReader config;
    private final WebElementService webElementService;

    public WebElementRetentionServiceImpl(NamedParameterJdbcTemplate jdbc, SystemConfigReader config,
                                          WebElementService webElementService) {
        this.jdbc = jdbc;
        this.config = config;
        this.webElementService = webElementService;
    }

    @Override
    @Transactional
    public WebElementLedgerRetentionResult sweep() {
        int retired = retireAgedCandidates();
        int purged = purgeAgedRetired();
        return new WebElementLedgerRetentionResult(retired, purged);
    }

    private int retireAgedCandidates() {
        int ttlDays = config.getInt(KEY_CANDIDATE_TTL_DAYS, DEFAULT_DAYS);
        List<long[]> aged = jdbc.query("""
                SELECT id, revision FROM tp_web_element
                 WHERE state = 0 AND created_at < now() - make_interval(days => :days)
                 ORDER BY id
                """, new MapSqlParameterSource("days", ttlDays),
                (rs, n) -> new long[]{rs.getLong("id"), rs.getInt("revision")});
        int moved = 0;
        for (long[] row : aged) {
            try {
                webElementService.changeState(row[0], WebElementLifecycle.RETIRED, (int) row[1]);
                moved++;
            } catch (ServiceException e) {
                // Concurrent edit won the race: the row moves next round.
                log.debug("候选自动退役跳过 id={}: {}", row[0], e.getMessage());
            }
        }
        return moved;
    }

    private int purgeAgedRetired() {
        int retentionDays = config.getInt(KEY_RETENTION_DAYS, DEFAULT_DAYS);
        List<Long> doomed = jdbc.query("""
                SELECT id FROM tp_web_element
                 WHERE state = 3 AND updated_at < now() - make_interval(days => :days)
                 ORDER BY id
                """, new MapSqlParameterSource("days", retentionDays),
                (rs, n) -> rs.getLong("id"));
        if (doomed.isEmpty()) {
            return 0;
        }
        // job_ref stores the element id as text; keep the two delete sets in
        // their own typed parameter sources (id BIGINT vs job_ref VARCHAR).
        jdbc.update("DELETE FROM tp_ai_task_event WHERE job_kind = 'element-ledger'"
                        + " AND job_ref IN (:refs)",
                new MapSqlParameterSource("refs", textIds(doomed)));
        // anchor stats leave through the ON DELETE CASCADE on element_id
        int removed = jdbc.update("DELETE FROM tp_web_element WHERE id IN (:ids)",
                new MapSqlParameterSource("ids", doomed));
        return removed;
    }

    private static List<String> textIds(List<Long> ids) {
        return ids.stream().map(String::valueOf).toList();
    }
}
