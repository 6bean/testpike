package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionSignalView;
import com.sixbean.testpike.core.aichat.api.dto.EvolutionTrendView;
import com.sixbean.testpike.core.aichat.domain.component.DraftObjectType;
import com.sixbean.testpike.core.aichat.domain.service.EvolutionSignalService;
import com.sixbean.testpike.core.aichat.domain.service.EvolutionTrendService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Before/after trend report of applied self-evolution proposals (AF-i phase 4).
 * <p>The comparison is single-sourced by construction: both sides of the
 * window are the same {@link EvolutionSignalService#observe} rollup the
 * proposal run reads, shifted one natural month. A completed comparison month
 * judges each APPLIED prompt card — improved when the follow-up rate drops or
 * the complaint count drops, ineffective when the same complaint shape
 * persists, insufficient when the window holds no sealed answer at all (the
 * report never pretends a verdict). Ineffective marks land on the draft row's
 * context json with the compared month, so the outcome rides the card instead
 * of piling up unseen; a recompute from live ledgers replaces a stale mark.
 * <p>Honesty rules: a comparison month still inside the calendar stays
 * "watching" and writes nothing; the report never lands, rolls back or
 * publishes anything — it only reads and stamps the verdict.
 */
@Service
public class EvolutionTrendServiceImpl implements EvolutionTrendService {

    private static final Logger log = LoggerFactory.getLogger(EvolutionTrendServiceImpl.class);
    private static final DateTimeFormatter MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM");

    static final String VERDICT_WATCHING = "watching";
    static final String VERDICT_INSUFFICIENT = "insufficient";
    static final String VERDICT_IMPROVED = "improved";
    static final String VERDICT_INEFFECTIVE = "ineffective";

    private final EvolutionSignalService signalService;
    private final NamedParameterJdbcTemplate named;

    public EvolutionTrendServiceImpl(EvolutionSignalService signalService,
                                     NamedParameterJdbcTemplate named) {
        this.signalService = signalService;
        this.named = named;
    }

    @Override
    public EvolutionTrendView trend(String month, Long projectId) {
        // Fail-closed key first: the observation door's own guard runs before
        // anything reads or writes, so an impossible month never stamps a row.
        EvolutionSignalView before = signalService.observe(month, projectId);
        if (projectId == null) {
            throw new ServiceException(
                    "趋势报告需要选定项目（提案卡落在具体项目的进化会话里，全平台视图不支持出报告）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        YearMonth runMonth = YearMonth.parse(month, MONTH_FORMAT);
        YearMonth afterMonth = runMonth.plusMonths(1);
        String afterMonthKey = afterMonth.format(MONTH_FORMAT);
        boolean afterMonthComplete =
                !LocalDate.now().isBefore(afterMonth.plusMonths(1).atDay(1));

        List<Object[]> cards = appliedPromptCards(month, projectId);
        if (!afterMonthComplete) {
            // Too early to judge: honest watching, zero writes.
            List<EvolutionTrendView.CardRow> rows = new ArrayList<>();
            for (Object[] card : cards) {
                rows.add(new EvolutionTrendView.CardRow((Long) card[0], (String) card[1],
                        VERDICT_WATCHING, before.followUpRate(), 0.0,
                        before.thumbsDownCount(), 0));
            }
            return new EvolutionTrendView(month, projectId, afterMonthKey, false, rows);
        }

        EvolutionSignalView after = signalService.observe(afterMonthKey, projectId);
        List<EvolutionTrendView.CardRow> rows = new ArrayList<>();
        for (Object[] card : cards) {
            long draftId = (Long) card[0];
            String verdict = judge(before, after);
            stampVerdict(draftId, verdict, afterMonthKey);
            rows.add(new EvolutionTrendView.CardRow(draftId, (String) card[1], verdict,
                    before.followUpRate(), after.followUpRate(),
                    before.thumbsDownCount(), after.thumbsDownCount()));
        }
        log.info("evolution trend month={} project={} after={} cards={}",
                month, projectId, afterMonthKey, rows.size());
        return new EvolutionTrendView(month, projectId, afterMonthKey, true, rows);
    }

    /**
     * Verdict for one applied card: no sealed answer either way proves
     * nothing; a lower follow-up rate or a lower complaint count improved;
     * the same complaint shape persisting is ineffective.
     */
    private static String judge(EvolutionSignalView before, EvolutionSignalView after) {
        if (after.sessionsWithSealedAnswer() == 0) {
            return VERDICT_INSUFFICIENT;
        }
        boolean rateImproved = after.followUpRate() < before.followUpRate();
        boolean fewerComplaints = before.thumbsDownCount() > 0
                && after.thumbsDownCount() < before.thumbsDownCount();
        return rateImproved || fewerComplaints ? VERDICT_IMPROVED : VERDICT_INEFFECTIVE;
    }

    /** APPLIED prompt-family cards of one run window (pending/voided carry no verdict). */
    private List<Object[]> appliedPromptCards(String month, Long projectId) {
        return named.query("""
                SELECT d.id, d.payload_json::json ->> 'name' AS target_name
                FROM tp_ai_chat_draft d
                JOIN tp_ai_chat_session s ON s.id = d.session_id
                WHERE d.object_type = :objectType AND d.status = 2
                  AND d.payload_json::json ->> 'subKind' = 'PROMPT_TPL'
                  AND s.project_id = :projectId AND s.title = :title
                ORDER BY d.id
                """, new MapSqlParameterSource()
                .addValue("objectType", DraftObjectType.EVOLUTION_PROPOSAL.wireName)
                .addValue("projectId", projectId)
                .addValue("title", "自进化提案 " + month),
                (rs, i) -> new Object[]{rs.getLong("id"), rs.getString("target_name")});
    }

    /**
     * Stamp the verdict on the draft row: ineffective always marks (no silent
     * pile-up); an improved/insufficient verdict only marks when a stale mark
     * from an earlier report exists, so the row never contradicts the ledger.
     * The replace-branch is picked Java-side — comparing two untyped bind
     * parameters inside the WHERE would leave Postgres guessing the type.
     */
    private void stampVerdict(long draftId, String verdict, String comparedMonth) {
        if (VERDICT_WATCHING.equals(verdict)) {
            return;
        }
        boolean alwaysMark = VERDICT_INEFFECTIVE.equals(verdict);
        String sql = """
                UPDATE tp_ai_chat_draft
                SET context_json = coalesce(context_json, '{}'::jsonb)
                        || jsonb_build_object('trendVerdict', :verdict,
                                              'trendComparedMonth', :comparedMonth),
                    updated_at = now()
                WHERE id = :id AND status = 2
                """ + (alwaysMark ? "" : "  AND context_json ->> 'trendVerdict' IS NOT NULL\n");
        int written = named.update(sql, new MapSqlParameterSource()
                .addValue("verdict", verdict)
                .addValue("comparedMonth", comparedMonth)
                .addValue("id", draftId));
        if (written > 0) {
            log.info("evolution trend mark draft={} verdict={} compared={}",
                    draftId, verdict, comparedMonth);
        }
    }
}
