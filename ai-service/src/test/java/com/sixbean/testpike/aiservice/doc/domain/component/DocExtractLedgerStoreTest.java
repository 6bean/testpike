package com.sixbean.testpike.aiservice.doc.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Extraction coverage ledger storage contract : the
 * first ledger of a (document, generation) inserts the canonical step form; a
 * replayed identical set writes nothing (reuses the dialog
 * {@link com.sixbean.testpike.aiservice.aichat.domain.component.GoalPlanLedger}
 * rules — single source, no second state machine); a real change updates the
 * steps and bumps the revision; unparsable input never reaches the database;
 * the chunk→step mapping marks a chunk done only when a requirement cites it
 * (anti fake-done) and leaves id-less summary blocks honestly untracked.
 * <p>golden3 recalibration: a citation settles a chunk only when its excerpt
 * quotes the chunk verbatim (the anchor gate's own containment test, applied
 * through {@link EvidenceChunkMatcher#normalise} — no second copy). The sole-
 * group fallback stamps a chunkId onto paraphrased placeholder rows too, and
 * counting those let a paraphrased placeholder row close books the
 * requirements never
 * really covered (live 8639 recall 0.880 -> 0.740 with the books reading zero).
 * <p>L2 posture: the jdbc door is mocked (infrastructure isolation, same face
 * as every other storage test); real extraction quality lives at L4.
 */
class DocExtractLedgerStoreTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final DocExtractLedgerStore underTest = new DocExtractLedgerStore(jdbc);

    private static final long DOC = 7L;
    private static final String TOKEN = "gen-token-e2e-001";
    private static final String LOGIN = "用户连续输错密码五次后账号锁定十分钟";
    private static final String EXPORT = "报表导出支持按月归档并保留最近二十四个月";

    private static Map<String, Object> chunk(long id, String content) {
        return Map.of("id", id, "seq", 1, "chapter", "安全", "content", content);
    }

    /** An unattributed row (no anchor at all). */
    private static Map<String, Object> item(String title, Long chunkId) {
        return chunkId == null
                ? Map.of("title", title)
                : Map.of("title", title, "chunkId", chunkId);
    }

    /** A citation row: anchor + the verbatim excerpt it was attributed with. */
    private static Map<String, Object> item(String title, Long chunkId, String evidence) {
        return Map.of("title", title, "chunkId", chunkId, "evidence", evidence);
    }

    // ---- pure mapping side (chunk x item -> canonical steps) ----

    /**
     * golden3 regression guard: a stamped anchor whose excerpt quotes nothing
     * verbatim is a placeholder (the "closest-constraint" row the model is
     * allowed to emit when it claims a block holds no testable requirement),
     * not a citation — it never settles the books and the reconcile plane
     * keeps mining the shortfall. The both-directions face (verbatim excerpt
     * settles) guards the gate never over-rejects.
     */
    @Test
    void stampedParaphraseRow_neverSettlesTheBooks_chunkStaysGapAndDeficit() {
        List<Map<String, Object>> chunks = List.of(chunk(101L, LOGIN));
        List<Map<String, Object>> placeholder =
                List.of(item("最接近约束：系统应尽量安全", 101L, "账号会被锁定一段时间"));
        assertEquals(1, DocExtractLedgerStore.uncoveredChunks(chunks, placeholder).size(),
                "paraphrased excerpt quotes nothing verbatim -> not a citation");
        assertEquals(1, DocExtractLedgerStore.coverageDeficit(chunks, placeholder),
                "the shortfall must survive the placeholder row");
        String steps = DocExtractLedgerStore.buildStepsJson(chunks, placeholder);
        assertTrue(steps.contains("\"state\":\"pending\""),
                "pending must stay honest on the residual face: " + steps);
        List<Map<String, Object>> anchored = List.of(item("登录失败锁定", 101L, LOGIN));
        assertEquals(0, DocExtractLedgerStore.uncoveredChunks(chunks, anchored).size());
        assertEquals(0, DocExtractLedgerStore.coverageDeficit(chunks, anchored));
    }

    @Test
    void stampedRowWithoutEvidence_neverSettlesTheBooks() {
        // The fallback stamps the sole block even when the model omitted the
        // excerpt field — nothing to prove containment with, stays a gap.
        List<Map<String, Object>> chunks = List.of(chunk(101L, LOGIN));
        assertEquals(1, DocExtractLedgerStore.coverageDeficit(
                chunks, List.of(item("登录失败锁定", 101L))));
    }

    @Test
    void buildSteps_citedChunkDoneWithEvidence_uncitedChunkPending() {
        String steps = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(101L, "甲"), chunk(102L, "乙")),
                List.of(item("登录超时锁定", 101L, "甲"), item("无锚条目", null)));
        assertTrue(steps.contains("\"title\":\"块#101\""), steps);
        assertTrue(steps.contains("\"state\":\"done\""), steps);
        assertTrue(steps.contains("登录超时锁定"), steps);
        assertTrue(steps.contains("\"title\":\"块#102\""), steps);
        assertTrue(steps.contains("\"state\":\"pending\""), steps);
    }

    @Test
    void buildSteps_summaryBlocksWithoutId_areHonestlyUntracked() {
        // F5 compressed run: nothing in the ledger (unanchorable != gap).
        assertNull(DocExtractLedgerStore.buildStepsJson(
                List.of(Map.of("seq", 1, "content", "摘要正文")), List.of(item("任意", null))));
    }

    @Test
    void uncoveredChunks_returnsOnlyAnchorableUncitedBlocks() {
        List<Map<String, Object>> gaps = DocExtractLedgerStore.uncoveredChunks(
                List.of(chunk(101L, "甲"), chunk(102L, "乙"), Map.of("seq", 9, "content", "摘要")),
                List.of(item("登录超时锁定", 101L, "甲")));
        assertEquals(1, gaps.size());
        assertEquals(102L, ((Number) gaps.get(0).get("id")).longValue());
    }

    // ---- item-count granularity (dense block gap) ----
    // A block carrying several enumerated requirement entries counts as covered
    // only once every entry has a citing item — one item out of a two-entry
    // block is a shortfall, not coverage (live 8905: the reconcile round stopped
    // early because the boolean rule treated dense blocks as done).

    private static final String TWO_ENTRY_BLOCK = "1." + LOGIN + "\n2." + EXPORT;

    @Test
    void denseBlock_oneOfTwoItemsCited_staysAGap_untilTheItemCountIsSatisfied() {
        List<Map<String, Object>> chunks = List.of(chunk(105L, TWO_ENTRY_BLOCK));
        assertEquals(1, DocExtractLedgerStore.uncoveredChunks(chunks, List.of()).size(),
                "nothing cited -> gap");
        assertEquals(1, DocExtractLedgerStore.uncoveredChunks(
                chunks, List.of(item("登录失败锁定", 105L, LOGIN))).size(),
                "one item out of a two-entry block -> still a gap (count granularity)");
        assertEquals(0, DocExtractLedgerStore.uncoveredChunks(
                chunks, List.of(item("登录失败锁定", 105L, LOGIN),
                        item("报表按月归档", 105L, EXPORT))).size(),
                "both entries cited -> covered");
    }

    @Test
    void denseBlock_partialCoverage_stepStaysPendingAndNamesChapterAndShortfall() {
        String steps = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(105L, TWO_ENTRY_BLOCK)), List.of(item("登录失败锁定", 105L, LOGIN)));
        assertTrue(steps.contains("\"state\":\"pending\""), steps);
        assertTrue(steps.contains("残账章节：安全"), steps);
        assertTrue(steps.contains("应得 2 条，已提 1 条"), steps);
    }

    @Test
    void denseBlock_fullCoverage_stepDoneWithCountInEvidence() {
        String steps = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(105L, TWO_ENTRY_BLOCK)),
                List.of(item("登录失败锁定", 105L, LOGIN), item("报表按月归档", 105L, EXPORT)));
        assertTrue(steps.contains("\"state\":\"done\""), steps);
        assertTrue(steps.contains("2 条需求点引用（应得 2 条）"), steps);
    }

    /**
 * identifier-led requirement lines are mechanical entries
 * requirement line still settled the books on one citation (live 8910: the
 * re-extraction round closed every gap block with one row while the second
 * marker of dense chapters stayed missing). The entry counter must
 * recognise them, while plain prose and date/revision lines keep the
 * legacy single-entry reading byte-identical.
 */
    @Test
    void identifierLedRequirementLines_countEveryEntryOfADenseBlock() {
        String twoRows = "RQ-0132：" + LOGIN + "\nREQ 7：" + EXPORT + "\n本节为流程说明背景材料。";
        List<Map<String, Object>> chunks = List.of(chunk(107L, twoRows));
        // One verbatim citation settles one of the two entries only: the block
        // stays a gap with a deficit of one until the second line is mined.
        assertEquals(1, DocExtractLedgerStore.uncoveredChunks(chunks,
                List.of(item("登录失败锁定", 107L, LOGIN))).size());
        assertEquals(1, DocExtractLedgerStore.coverageDeficit(chunks,
                List.of(item("登录失败锁定", 107L, LOGIN))));
        // Both entries cited -> honestly settled.
        assertEquals(0, DocExtractLedgerStore.coverageDeficit(chunks,
                List.of(item("登录失败锁定", 107L, LOGIN),
                        item("报表按月归档", 107L, EXPORT))));
    }

    /** Guard: dates, amounts and version prose never inflate the entry count. */
    @Test
    void proseAndNumericLines_keepTheLegacySingleEntryReading() {
        String prose = "2026年上线，预算 500000 元；v1.2 修订见内部纪要。\nURL:5000 端口按部署手册配置。";
        List<Map<String, Object>> chunks = List.of(chunk(108L, prose));
        assertEquals(0, DocExtractLedgerStore.coverageDeficit(chunks,
                List.of(item("上线计划", 108L, prose))));
    }

    @Test
    void coverageDeficit_sumsMissingItemsPerAnchorableChunk() {
        List<Map<String, Object>> chunks = List.of(
                chunk(105L, TWO_ENTRY_BLOCK), chunk(106L, "水印不得关闭"));
        assertEquals(3, DocExtractLedgerStore.coverageDeficit(chunks, List.of()),
                "2 entries + 1 plain block -> three items missing");
        assertEquals(2, DocExtractLedgerStore.coverageDeficit(
                chunks, List.of(item("登录失败锁定", 105L, LOGIN))));
        assertEquals(0, DocExtractLedgerStore.coverageDeficit(
                chunks, List.of(item("登录失败锁定", 105L, LOGIN),
                        item("报表按月归档", 105L, EXPORT),
                        item("水印", 106L, "水印不得关闭"))));
    }

    // ---- : behaviour-sentence counting for marker-free prose ----
    // Requirement prose without line markers (the dense "wall of text" shape
    // behind the recall loss, live 8491/8639) carries one testable entry per
    // requirement sentence, so the mechanical counter must read the modal-verb
    // face of the text too: expected = max(marker lines, behaviour sentences,
    // 1). Posture stays conservative — an over-count only buys bounded
    // reconcile rounds and an honest residual note, never a fabricated or
    // deleted item. Zero model calls, pure structure, same face as the marker
    // rule above.

    @Test
    void proseRequirementSentences_countEachBehaviourSentence() {
        String prose = "系统必须支持单点登录。用户可查询最近三个月的历史报告。"
                + "管理员需要提供按月归档的导出入口。";
        assertEquals(3, DocExtractLedgerStore.expectedEntries(chunk(120L, prose)),
                "three behaviour sentences -> three mechanical entries");
    }

    @Test
    void proseDenseBlock_deficitFollowsBehaviourSentenceCount() {
        String prose = "系统必须支持单点登录。用户可查询历史报告。管理员需要提供导出入口。";
        List<Map<String, Object>> chunks = List.of(chunk(121L, prose));
        assertEquals(3, DocExtractLedgerStore.coverageDeficit(chunks, List.of()));
        assertEquals(2, DocExtractLedgerStore.coverageDeficit(chunks,
                List.of(item("单点登录", 121L, "系统必须支持单点登录"))),
                "one verbatim citation settles one of three sentences");
    }

    @Test
    void twoModalsInOneSentence_countOnce() {
        String sentence = "系统必须支持导入并可以按模板导出报表。";
        assertEquals(1, DocExtractLedgerStore.expectedEntries(chunk(122L, sentence)),
                "sentence granularity, not word granularity");
    }

    @Test
    void descriptiveProseWithoutModals_keepsSingleEntryReading() {
        String prose = "本节描述应用的整体架构与部署拓扑。2026年上线，预算 500000 元。";
        assertEquals(1, DocExtractLedgerStore.expectedEntries(chunk(123L, prose)),
                "应用/年份/金额 noun forms must not inflate the count");
    }

    @Test
    void markerLinesAndExtraProse_takeTheLargerCount() {
        String block = "1." + LOGIN + "\n2." + EXPORT
                + "\n用户应当可以导出明细。管理员需要提供归档入口。系统必须保留审计日志。";
        // the trailing line holds three sentences and its first carries both
        // reading.
        assertEquals(4, DocExtractLedgerStore.expectedEntries(chunk(124L, block)));
    }

    @Test
    void markerDenseBlock_behaviourFaceKeepsMarkerCount() {
        // Both faces of a fully marked block read 2 — max() must not inflate
        // when the behaviour face matches the marker face (zero-drift guard).
        String block = "1.系统必须支持单点登录。\n2.管理员需要提供归档入口。";
        assertEquals(2, DocExtractLedgerStore.expectedEntries(chunk(126L, block)));
    }

    @Test
    void markerOnlyBlock_zeroDrift_staysOnMarkerCount() {
        assertEquals(2, DocExtractLedgerStore.expectedEntries(chunk(125L, TWO_ENTRY_BLOCK)),
                "marker reading byte-identical to the legacy rule");
    }

    @Test
    void plainBlock_booleanCoverage_zeroDrift_expectedStaysOne() {
        // Marker-free prose: one citing item covers the block exactly as before.
        assertEquals(0, DocExtractLedgerStore.uncoveredChunks(
                List.of(chunk(101L, "甲")), List.of(item("登录超时锁定", 101L, "甲"))).size());
        assertEquals(1, DocExtractLedgerStore.coverageDeficit(
                List.of(chunk(101L, "甲")), List.of()), "uncited plain block -> deficit 1");
    }

    // ---- storage door (mocked jdbc = infrastructure isolation, L2 posture) ----

    @Test
    void firstLedger_insertsCanonicalSteps() {
        when(jdbc.queryForList(contains("SELECT"), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of());
        String steps = DocExtractLedgerStore.buildStepsJson(List.of(chunk(101L, "甲")), List.of());
        assertTrue(underTest.upsertIfChanged(DOC, TOKEN, steps));
        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT INTO tp_doc_extract_ledger"), captor.capture());
        assertEquals(DOC, ((Number) captor.getValue().getValue("documentId")).longValue());
        assertEquals(TOKEN, captor.getValue().getValue("extractToken"));
        assertTrue(String.valueOf(captor.getValue().getValue("steps")).contains("pending"));
    }

    @Test
    void replayedIdenticalSet_writesNothing() {
        String canonical = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(101L, "甲"), chunk(102L, "乙")),
                List.of(item("登录超时锁定", 101L, "甲")));
        when(jdbc.queryForList(contains("SELECT"), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of(canonical));
        // same semantics, producer reordered the rows: replay must be a zero-write no-op
        String reordered = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(102L, "乙"), chunk(101L, "甲")),
                List.of(item("登录超时锁定", 101L, "甲")));
        assertFalse(underTest.upsertIfChanged(DOC, TOKEN, reordered));
        verify(jdbc, never()).update(contains("INSERT"), any(MapSqlParameterSource.class));
        verify(jdbc, never()).update(contains("UPDATE"), any(MapSqlParameterSource.class));
    }

    @Test
    void realChange_updatesStepsAndBumpsRevision() {
        String stored = DocExtractLedgerStore.buildStepsJson(List.of(chunk(101L, "甲")), List.of());
        when(jdbc.queryForList(contains("SELECT"), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of(stored));
        String covered = DocExtractLedgerStore.buildStepsJson(
                List.of(chunk(101L, "甲")), List.of(item("登录超时锁定", 101L, "甲")));
        assertTrue(underTest.upsertIfChanged(DOC, TOKEN, covered));
        verify(jdbc).update(contains("UPDATE tp_doc_extract_ledger"), any(MapSqlParameterSource.class));
    }

    @Test
    void unparsableInput_neverReachesDatabase() {
        assertFalse(underTest.upsertIfChanged(DOC, TOKEN, "{not-json"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void anchorlessDoneIsDemotedBeforePersisting() {
        // hand-built fake-done step: normalize must downgrade it to pending
        // before anything reaches the row (anti fake-done, reused rule).
        when(jdbc.queryForList(contains("SELECT"), any(MapSqlParameterSource.class), any(Class.class)))
                .thenReturn(List.of());
        assertTrue(underTest.upsertIfChanged(DOC, TOKEN,
                "[{\"title\":\"块#101\",\"state\":\"done\",\"evidence\":\"\"}]"));
        ArgumentCaptor<MapSqlParameterSource> captor =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("INSERT"), captor.capture());
        assertTrue(String.valueOf(captor.getValue().getValue("steps")).contains("pending"),
                String.valueOf(captor.getValue().getValue("steps")));
    }

    @Test
    void readDoorDown_answersNoLedgerWithoutThrowing() {
        when(jdbc.queryForList(contains("SELECT"), any(MapSqlParameterSource.class), any(Class.class)))
                .thenThrow(new RuntimeException("connection refused"));
        assertNull(underTest.findStepsJson(DOC, TOKEN));
    }
}
