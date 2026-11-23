package com.sixbean.testpike.aiservice.judge.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.judge.api.dto.QaGenJobView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Storage door of tp_qa_gen_job — the run ledger of the question-generation
 * job plane . One row per run: startRun inserts the
 * running row, every landed group updates the cumulative item face through
 * its own run token, finishRun closes the status. The run-token predicate on
 * every write is the preemption boundary: when a newer submit took over the
 * document, an old background thread can still only touch its own row and
 * never overwrite the newest one.
 * <p>Read door: findLatestByDocument answers the highest-id row, i.e. the
 * newest run for that document, or null when the document never took the job
 * face. Like the process-event door this read is advisory — a door outage
 * logs and answers null instead of failing the poller. The read also passes
 * through the QaGenQueryWindow stall verdict : a RUNNING row idle
 * past 2x the forward-read window answers failed in memory so a run killed
 * by a service restart cannot strand the poller forever; the stored row is
 * never rewritten by this derivation.
 */
@Component
public class QaGenJobStore {

    private static final Logger log = LoggerFactory.getLogger(QaGenJobStore.class);

    private static final String START_SQL =
            "INSERT INTO tp_qa_gen_job"
                    + " (document_id, run_token, project_id, model, status, total_groups,"
                    + "  done_groups, items_json, prompt_tokens, completion_tokens)"
                    + " VALUES (:documentId, :runToken, :projectId, :model, :status,"
                    + "  :totalGroups, 0, '[]', 0, 0)";

    private static final String LAND_SQL =
            "UPDATE tp_qa_gen_job"
                    + "   SET items_json = :itemsJson, done_groups = :doneGroups,"
                    + "       total_groups = :totalGroups, prompt_tokens = :promptTokens,"
                    + "       completion_tokens = :completionTokens,"
                    + "       updated_at = CURRENT_TIMESTAMP"
                    + " WHERE run_token = :runToken";

    private static final String FINISH_SQL =
            "UPDATE tp_qa_gen_job"
                    + "   SET status = :status, error_msg = :errorMsg,"
                    + "       updated_at = CURRENT_TIMESTAMP"
                    + " WHERE run_token = :runToken";

    private static final String LATEST_SQL =
            "SELECT document_id, run_token, status, total_groups, done_groups, items_json,"
                    + " prompt_tokens, completion_tokens, model, error_msg, updated_at"
                    + " FROM tp_qa_gen_job WHERE document_id = :documentId"
                    + " ORDER BY id DESC LIMIT 1";

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final QaGenQueryWindow queryWindow;

    public QaGenJobStore(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                         QaGenQueryWindow queryWindow) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.queryWindow = queryWindow;
    }

    /** Opens the row of one run in the running state. */
    public void startRun(long documentId, String runToken, long projectId,
                         String model, int totalGroups) {
        jdbc.update(START_SQL, new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("runToken", runToken)
                .addValue("projectId", projectId)
                .addValue("model", model)
                .addValue("status", QaGenJobView.STATUS_RUNNING)
                .addValue("totalGroups", totalGroups));
    }

    /** Persists one landed group: the cumulative merged items plus the token
     *  totals so far, bounded by the run token of the writing thread. */
    public void landGroup(long documentId, String runToken, String itemsJson,
                          int doneGroups, int totalGroups,
                          int promptTokens, int completionTokens) {
        jdbc.update(LAND_SQL, new MapSqlParameterSource()
                .addValue("itemsJson", itemsJson)
                .addValue("doneGroups", doneGroups)
                .addValue("totalGroups", totalGroups)
                .addValue("promptTokens", promptTokens)
                .addValue("completionTokens", completionTokens)
                .addValue("runToken", runToken));
    }

    /** Closes the run: done (errorMsg null) or failed with the human cause. */
    public void finishRun(String runToken, String status, String errorMsg) {
        jdbc.update(FINISH_SQL, new MapSqlParameterSource()
                .addValue("status", status)
                .addValue("errorMsg", errorMsg)
                .addValue("runToken", runToken));
    }

    /** Newest job row of the document, or null when no run ever started. */
    public QaGenJobView findLatestByDocument(long documentId) {
        try {
            List<QaGenJobView> rows = jdbc.query(LATEST_SQL,
                    new MapSqlParameterSource("documentId", documentId), (rs, rowNum) ->
                            queryWindow.converge(
                                    new QaGenJobView(rs.getLong("document_id"),
                                            rs.getString("run_token"),
                                            rs.getString("status"),
                                            rs.getInt("total_groups"),
                                            rs.getInt("done_groups"),
                                            readItemsQuiet(rs.getString("items_json")),
                                            rs.getInt("prompt_tokens"),
                                            rs.getInt("completion_tokens"),
                                            rs.getString("model"),
                                            rs.getString("error_msg")),
                                    rs.getTimestamp("updated_at")));
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            log.warn("造题作业行读取门不可达，按空答案继续 documentId={} reason={}",
                    documentId, doorDown.getMessage());
            return null;
        }
    }

    private List<Map<String, Object>> readItemsQuiet(String itemsJson) {
        if (itemsJson == null || itemsJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(itemsJson, List.class);
        } catch (Exception malformed) {
            log.warn("造题作业行 items_json 不可解析，按空清单继续 cause={}", malformed.toString());
            return List.of();
        }
    }
}
