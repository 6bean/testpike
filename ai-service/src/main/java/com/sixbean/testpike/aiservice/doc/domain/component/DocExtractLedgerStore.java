package com.sixbean.testpike.aiservice.doc.domain.component;

import com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedger;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Storage door of tp_doc_extract_ledger : one coverage
 * ledger per (document, extraction generation). The step state machine is NOT
 * re-implemented here — normalisation, the anti fake-done guard and the
 * set-equality replay test are reused from the dialog
 * {@link GoalPlanLedger} rules (single source), this door only maps chunks and
 * items onto that canonical step shape and owns the SQL.
 * <p>A chunk counts as done only when at least one extracted requirement is
 * attributed back to it ({@code chunkId} stamped by the evidence matcher);
 * an uncovered chunk stays pending so the preview can flag it honestly.
 * Blocks without an exposed row id (F5 summary blocks) never enter the
 * ledger — an unanchorable block is not a gap, it is untracked.
 * <p>Reads are fail-soft: an unreachable door answers "no ledger" instead of
 * throwing, mirroring the goal-plan posture.
 */
@Component
public final class DocExtractLedgerStore {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(DocExtractLedgerStore.class);

    private static final String FIND_SQL =
            "SELECT steps_json FROM tp_doc_extract_ledger"
                    + " WHERE document_id = :documentId AND extract_token = :extractToken";

    // directed supplement: the newest row of one document (previous
    // generation) is the carry-over base so a supplement of one pending block
    // can never silently drop the OTHER pending blocks' rows.
    /** Verbatim honesty sentence for a directed supplement round that
 * still produced zero items for a rerun block ( ruling file
 * wording, single source for the merge face). */
    public static final String DIRECTED_RERUN_HONESTY = "复跑一轮仍无条目";

    private static final String FIND_LATEST_SQL =
            "SELECT steps_json FROM tp_doc_extract_ledger"
                    + " WHERE document_id = :documentId ORDER BY id DESC LIMIT 1";

    private static final String INSERT_SQL =
            "INSERT INTO tp_doc_extract_ledger (document_id, extract_token, steps_json, revision)"
                    + " VALUES (:documentId, :extractToken, :steps, 1)"
                    + " ON CONFLICT (document_id, extract_token) DO UPDATE"
                    + " SET steps_json = EXCLUDED.steps_json,"
                    + " revision = tp_doc_extract_ledger.revision + 1, updated_at = now()";

    private static final String UPDATE_SQL =
            "UPDATE tp_doc_extract_ledger SET steps_json = :steps,"
                    + " revision = revision + 1, updated_at = now()"
                    + " WHERE document_id = :documentId AND extract_token = :extractToken";

    // one stage row per step of the run (grouping i/n, final
    // bucket j/m, reconcile round). The WHERE guard keeps a replayed identical
    // label a zero-write (R28 posture: no updated_at drift on no-change).
    private static final String STAGE_UPSERT_SQL =
            "INSERT INTO tp_doc_extract_stage"
                    + " (document_id, extract_token, stage_code, stage_label)"
                    + " VALUES (:documentId, :extractToken, :code, :label)"
                    + " ON CONFLICT (document_id, extract_token, stage_code) DO UPDATE"
                    + " SET stage_label = EXCLUDED.stage_label, updated_at = now()"
                    + " WHERE tp_doc_extract_stage.stage_label"
                    + " IS DISTINCT FROM EXCLUDED.stage_label";

    // golden-window evidence: the stall sweeper anchors on
    // tp_document.updated_at and assumes a healthy run keeps touching the row,
    // but the grouped direct-extraction path only ever touched tp_doc_extract_stage.
    // A live 642-block run at 31 minutes was therefore reclaimed mid-flight.
    // Every stage boundary now also heartbeats the task row. The guard keeps
    // the lease honest: only an extracting row of this exact generation is
    // touched, so a zombie consumer of a swept generation can never extend
    // the lease of a fresh run. Best-effort like the stage write itself.
    private static final String TASK_HEARTBEAT_SQL =
            "UPDATE tp_document SET updated_at = now()"
                    + " WHERE id = :documentId AND status = 3 AND extract_token = :extractToken";

    private final NamedParameterJdbcTemplate jdbc;

    public DocExtractLedgerStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Stored canonical steps of one generation ledger, or null (absent / door down). */
    public String findStepsJson(long documentId, String extractToken) {
        try {
            List<String> rows = jdbc.queryForList(FIND_SQL, params(documentId, extractToken, null),
                    String.class);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            log.warn("提取覆盖账本读取门不可达，按无账继续 documentId={} reason={}",
                    documentId, doorDown.getMessage());
            return null;
        }
    }

    /**
 * Newest stored steps of one document across generations (the directed
 * supplement carry-over base, ). Fail-soft like the generation
 * read: an unreachable door answers null and the round keeps its own
 * honest books — a supplement never fails because the ledger door is
 * briefly down, it only loses the rows it could not read.
 */
    public String findLatestStepsJson(long documentId) {
        try {
            List<String> rows = jdbc.queryForList(FIND_LATEST_SQL,
                    new MapSqlParameterSource("documentId", documentId), String.class);
            return rows.isEmpty() ? null : rows.get(0);
        } catch (RuntimeException doorDown) {
            log.warn("提取覆盖账本最新行不可达，补提轮按无上一代承接继续 documentId={} reason={}",
                    documentId, doorDown.getMessage());
            return null;
        }
    }

    /**
     * Persist (or re-persist) a coverage ledger for one generation.
     *
     * @return true when the row was created or updated, false when the input
     *         is unparsable or equals the stored step set (zero-write replay)
     */
    public boolean upsertIfChanged(long documentId, String extractToken, String stepsJson) {
        String canonical = GoalPlanLedger.normalize(stepsJson);
        if (canonical == null) {
            // Unparsable input never reaches the database — a corrupt step
            // list would poison every later reading of this generation.
            return false;
        }
        String existing = findStepsJson(documentId, extractToken);
        if (existing == null) {
            jdbc.update(INSERT_SQL, params(documentId, extractToken, canonical));
            return true;
        }
        if (GoalPlanLedger.sameSet(existing, canonical)) {
            return false;
        }
        jdbc.update(UPDATE_SQL, params(documentId, extractToken, canonical));
        return true;
    }

    /**
 * Canonical coverage steps for one round: one step per anchorable chunk
 * (chunks carrying a numeric row id), done with a citing-requirement
 * evidence string when every mechanical entry of the chunk has at least
 * one item attributed to it, pending otherwise. A dense block (several
 * enumerated requirement entries) is only covered once the citing item
 * count reaches its entry count — one item out of a two-entry block is a
 * shortfall, not coverage .
 * Pending steps carry a note naming the chapter and the shortfall so the
 * residual account can be pointed at chapter by chapter. Empty when no
 * chunk is anchorable (pure summary path — honestly untracked, never
 * fabricated gaps).
 */
    public static String buildStepsJson(List<Map<String, Object>> chunks,
                                        List<Map<String, Object>> items) {
        Map<Long, List<String>> cited = citedTitlesByChunk(chunks, items);
        List<Map<String, Object>> steps = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Long chunkId = anchorableId(chunk);
            if (chunkId == null) {
                continue;
            }
            List<String> titles = cited.get(chunkId);
            int citedCount = titles == null ? 0 : titles.size();
            int expected = expectedEntries(chunk);
            boolean covered = citedCount >= expected;
            Map<String, Object> step = new java.util.LinkedHashMap<>();
            step.put("title", "块#" + chunkId);
            step.put("state", covered ? "done" : "pending");
            step.put("evidence", covered ? (expected > 1
                    ? citedCount + " 条需求点引用（应得 " + expected + " 条）："
                            + String.join("、", titles)
                    : citedCount + " 条需求点引用：" + String.join("、", titles)) : "");
            if (!covered) {
                String chapter = ContextBudgetPlanner.chapterOf(chunk);
                StringBuilder residual = new StringBuilder("残账章节：")
                        .append(chapter.isBlank() ? "未标注" : chapter);
                residual.append(expected > 1
                        ? "；应得 " + expected + " 条，已提 " + citedCount + " 条"
                        : "；未提取任何条目");
                step.put("note", residual.toString());
            }
            steps.add(step);
        }
        if (steps.isEmpty()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(steps);
        } catch (Exception neverHappensForPlainMaps) {
            return null;
        }
    }

    /**
 * Land one stage row of the running extraction . Writes
 * are fail-soft by design: the stage face is progress bookkeeping, a door
 * outage must never fail the extraction itself (same non-fatal posture as
 * the coverage ledger write in the reconcile plane).
 */
    public void recordStage(long documentId, String extractToken, String stageCode,
                            String label) {
        try {
            jdbc.update(TASK_HEARTBEAT_SQL, new MapSqlParameterSource()
                    .addValue("documentId", documentId)
                    .addValue("extractToken", extractToken));
        } catch (RuntimeException doorDown) {
            log.warn("提取心跳落写失败（不影响提取） documentId={} token={} reason={}",
                    documentId, extractToken, doorDown.getMessage());
        }
        try {
            jdbc.update(STAGE_UPSERT_SQL, new MapSqlParameterSource()
                    .addValue("documentId", documentId)
                    .addValue("extractToken", extractToken)
                    .addValue("code", stageCode)
                    .addValue("label", label));
        } catch (RuntimeException doorDown) {
            log.warn("提取阶段台账落账失败（不影响提取） documentId={} token={} stage={} reason={}",
                    documentId, extractToken, stageCode, doorDown.getMessage());
        }
    }

    /** Anchorable chunks whose citing item count is still below the number of
 * mechanical requirement entries they contain a dense
 * block with one item cited is a residual gap, not coverage). */
    public static List<Map<String, Object>> uncoveredChunks(List<Map<String, Object>> chunks,
                                                            List<Map<String, Object>> items) {
        Map<Long, List<String>> cited = citedTitlesByChunk(chunks, items);
        List<Map<String, Object>> gaps = new ArrayList<>();
        for (Map<String, Object> chunk : chunks) {
            Long chunkId = anchorableId(chunk);
            if (chunkId == null) {
                continue;
            }
            List<String> titles = cited.get(chunkId);
            int citedCount = titles == null ? 0 : titles.size();
            if (citedCount < expectedEntries(chunk)) {
                gaps.add(chunk);
            }
        }
        return gaps;
    }

    /**
 * Directed supplement merge : the rerun
 * only ever saw the whitelisted blocks, so the honest books of the new
 * generation are the PREVIOUS generation's rows with the rerun blocks'
 * steps replaced. A replaced step that still reads pending with zero
 * evidence (the note stays untouched — the residual chapter account is
 * already on the preview face); a step that already carries the sentence
 * keeps it exactly once, so a replay of the same round rebooks nothing
 * (R28 zero-write posture, guarded again by upsertIfChanged). Blocks the
 * round did not rerun never lose their rows, and a block nobody ever
 * tracked never appears: no fabricated coverage either way.
 *
 * @param previousStepsJson newest stored steps (null/blank/unparsable =
 * no carry base, the round's own steps stand)
 * @param currentStepsJson this round's steps over the narrowed face
 * @param rerunChunkIds the whitelisted ids the round actually re-read
 */
    public static String mergeDirectedSupplement(String previousStepsJson,
                                                 String currentStepsJson,
                                                 java.util.Collection<Long> rerunChunkIds) {
        java.util.Set<String> rerunTitles = new java.util.HashSet<>();
        for (Long id : rerunChunkIds) {
            if (id != null) {
                rerunTitles.add("块#" + id);
            }
        }
        java.util.List<Map<String, Object>> current = parseStepsOrNull(currentStepsJson);
        if (current == null) {
            return currentStepsJson;
        }
        java.util.List<Map<String, Object>> merged = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        java.util.List<Map<String, Object>> previous = parseStepsOrNull(previousStepsJson);
        if (previous != null) {
            merged.addAll(previous);
            for (Map<String, Object> step : previous) {
                seen.add(String.valueOf(step.get("title")));
            }
        }
        for (int i = 0; i < current.size(); i++) {
            Map<String, Object> fresh = withRerunHonesty(current.get(i), rerunTitles);
            String title = String.valueOf(fresh.get("title"));
            if (!seen.add(title)) {
                for (int j = 0; j < merged.size(); j++) {
                    if (title.equals(String.valueOf(merged.get(j).get("title")))) {
                        merged.set(j, fresh);
                        break;
                    }
                }
            } else {
                merged.add(fresh);
            }
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(merged);
        } catch (Exception neverHappensForPlainMaps) {
            return currentStepsJson;
        }
    }

    /** Zero-output pending steps of a rerun block carry the honesty sentence once. */
    private static Map<String, Object> withRerunHonesty(Map<String, Object> step,
                                                        java.util.Set<String> rerunTitles) {
        if (!"pending".equals(String.valueOf(step.get("state")))
                || !rerunTitles.contains(String.valueOf(step.get("title")))) {
            return step;
        }
        String note = String.valueOf(step.getOrDefault("note", ""));
        if (!note.contains("未提取任何条目")) {
            return step; // a partial shortfall already states its own numbers
        }
        String evidence = String.valueOf(step.getOrDefault("evidence", ""));
        if (evidence.isBlank() || "null".equals(evidence)) {
            step.put("evidence", DIRECTED_RERUN_HONESTY);
        } else if (!evidence.contains(DIRECTED_RERUN_HONESTY)) {
            step.put("evidence", evidence + "；" + DIRECTED_RERUN_HONESTY);
        }
        return step;
    }

    private static java.util.List<Map<String, Object>> parseStepsOrNull(String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return null;
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(stepsJson,
                    new com.fasterxml.jackson.core.type.TypeReference<
                            java.util.List<Map<String, Object>>>() {
                    });
        } catch (Exception corrupt) {
            return null;
        }
    }

    /**
     * Remaining shortfall in items across all anchorable chunks — the progress
     * metric the reconcile loop early-stops on. Count granularity lives here
     * so a round that cites one more entry of a dense block counts as gain
     * even while the gap-chunk count stays the same (boolean size comparison
     * silently stopped the 8905 loop after round 1). Zero model calls, pure
     * mechanical reading, same posture as the fidelity ledger.
     */
    public static int coverageDeficit(List<Map<String, Object>> chunks,
                                      List<Map<String, Object>> items) {
        Map<Long, List<String>> cited = citedTitlesByChunk(chunks, items);
        int deficit = 0;
        for (Map<String, Object> chunk : chunks) {
            Long chunkId = anchorableId(chunk);
            if (chunkId == null) {
                continue;
            }
            List<String> titles = cited.get(chunkId);
            int citedCount = titles == null ? 0 : titles.size();
            deficit += Math.max(0, expectedEntries(chunk) - citedCount);
        }
        return deficit;
    }

    /**
 * Mechanical entry count of one chunk: lines that start like an enumerated
 * live 8910: dense blocks with identifier-led rows settled the books on a
 * single citation while their second requirement stayed unmined).
 * adds the behaviour-sentence face: marker-free requirement prose
 * (the dense "wall of text" behind the recall loss, live 8491/8639)
 * carries one testable entry per requirement sentence, counted by the
 * zero model calls. expected = max(marker lines, behaviour sentences, 1).
 * Sentence granularity keeps two modals in one sentence at one entry; the
 * prose keeps the legacy single-entry reading. Deliberately conservative:
 * an over-count merely earns bounded extra reconcile rounds and an honest
 * residual note, never a fabricated or deleted item.
 */
    static int expectedEntries(Map<String, Object> chunk) {
        String content = ContextBudgetPlanner.contentOf(chunk);
        if (content.isEmpty()) {
            return 1;
        }
        int markers = 0;
        for (String line : content.split("\\R")) {
            if (ENTRY_LINE.matcher(line).find()) {
                markers++;
            }
        }
        int behaviourSentences = 0;
        for (String sentence : content.split("[。！？；]+|\\R")) {
            if (BEHAVIOUR_SENTENCE.matcher(sentence).find()) {
                behaviourSentences++;
            }
        }
        return Math.max(Math.max(markers, behaviourSentences), 1);
    }

    /** modal-verb family of one requirement sentence (enumerated set
 * of the adjudication, guards on the two single-character tokens keep
 * noun compounds out of the count). */
    private static final java.util.regex.Pattern BEHAVIOUR_SENTENCE =
            java.util.regex.Pattern.compile(
                    "必须|应当|禁止|支持|提供|需|可(?!能)"
                            + "|(?<![反供答适属])应(?!用|急|变)");

    private static final java.util.regex.Pattern ENTRY_LINE = java.util.regex.Pattern.compile(
            "^\\s*(?:\\d{1,3}[、.．)）]|[（(]\\s*\\d{1,3}\\s*[)）]"
                    + "|[一二三四五六七八九十]{1,3}[、.．]|[•·]|[-*]\\s"
                    // identifier-led requirement rows carry
                    // prose word followed by other punctuation never matches.
                    + "|[A-Za-z]{1,8}[-_# ]?\\d{1,5}\\s*[：:])");

    /** chunkId -> titles of the items attributed to it (evidence of the done
     *  state). golden3 recalibration: a stamp alone settles nothing — the
     *  excerpt must quote the cited chunk verbatim under the anchor gate's
     *  own containment test ({@link EvidenceChunkMatcher#normalise}, the one
     *  normalisation source). The sole-group fallback stamps chunkIds onto
     *  paraphrased rows too; counting those let a closest-constraint
     *  placeholder row close books the requirements never really covered (live 8639 recall 0.880 -> 0.740 with the
     *  ledger reading zero). */
    private static Map<Long, List<String>> citedTitlesByChunk(
            List<Map<String, Object>> chunks, List<Map<String, Object>> items) {
        Map<Long, List<String>> byChunk = new HashMap<>();
        if (items == null) {
            return byChunk;
        }
        Map<Long, String> haystacks = new HashMap<>();
        if (chunks != null) {
            for (Map<String, Object> chunk : chunks) {
                Long id = anchorableId(chunk);
                if (id != null) {
                    haystacks.put(id, EvidenceChunkMatcher.normalise(
                            com.sixbean.testpike.aiservice.doc.domain.component
                                    .ContextBudgetPlanner.contentOf(chunk)));
                }
            }
        }
        for (Map<String, Object> item : items) {
            Object rawId = item.get("chunkId");
            if (!(rawId instanceof Number)) {
                continue;
            }
            Object evidence = item.get("evidence");
            String needle = evidence == null ? ""
                    : EvidenceChunkMatcher.normalise(String.valueOf(evidence));
            String haystack = haystacks.get(((Number) rawId).longValue());
            if (needle.isEmpty() || haystack == null || !haystack.contains(needle)) {
                continue; // stamped but nothing verbatim-anchored -> settles no book
            }
            Object title = item.get("title");
            byChunk.computeIfAbsent(((Number) rawId).longValue(), k -> new ArrayList<>())
                    .add(title == null ? "（无标题）" : String.valueOf(title));
        }
        return byChunk;
    }

    /** Public: the same anchorable-row test the ledger books on (single
     *  source, consumed by the directed supplement narrowing as well). */
    public static Long anchorableId(Map<String, Object> chunk) {
        Object id = chunk.get("id");
        return id instanceof Number ? ((Number) id).longValue() : null;
    }

    private static MapSqlParameterSource params(long documentId, String extractToken, String steps) {
        MapSqlParameterSource source = new MapSqlParameterSource()
                .addValue("documentId", documentId)
                .addValue("extractToken", extractToken);
        if (steps != null) {
            source.addValue("steps", steps);
        }
        return source;
    }
}
