package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingKernel;
import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchRequest;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Grounding assembly contract : the
 * dialog answer must stand on real help chapters and knowledge chunks, or
 * honestly say there is nothing to stand on. Pins, in order:
 * <ol>
 * <li>a help chapter whose title shares terms with the question lands as a
 * {@code help} citation carrying its domain anchor;</li>
 * <li>knowledge chunks feed {@code knowledge} citations and the retrieval
 * call keeps the project segment + BUSINESS scene scope (H1 isolation —
 * the dialog never widens the search beyond the asking project);</li>
 * <li>a broken retrieval source degrades fail-soft to the honest zero-hit
 * shape instead of failing the whole turn;</li>
 * <li>the K8 budget keys override the seeded defaults when present.</li>
 * </ol>
 * Infrastructure (core HTTP gateway, vector search service) is mocked at L2
 * per protocol; the live admin→ai-service→core chain is exercised by the L3
 * segment that follows.
 */
class GroundingServiceTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    /** Ledger read side (V339 revision column); mocked at L2 as infrastructure. */
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate ledgerJdbc =
            mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    private final GroundingService service =
            new GroundingService(gateway, searchService, ledgerJdbc);

    private static CoreGateway.HelpDocBrief chapter(long id, String domain, String title) {
        return new CoreGateway.HelpDocBrief(id, domain, title, null, 1);
    }

    /** Index rows carry no body; the fetched domain detail does. */
    private static CoreGateway.HelpDocBrief fullChapter(long id, String domain, String title,
                                                        String body) {
        return new CoreGateway.HelpDocBrief(id, domain, title, body, 1);
    }

    @Test
    void helpChapterMatchingTheQuestionBecomesAnchoredCitation() {
        when(gateway.listHelpDocs()).thenReturn(List.of(
                chapter(8, "A", "从需求一键生成结构化用例"),
                chapter(120, "AF", "升级中心：平台版本与一键升级")));
        when(gateway.getHelpDomainChapters("AF")).thenReturn(List.of(
                fullChapter(120, "AF", "升级中心：平台版本与一键升级",
                        "升级中心展示当前平台版本，检测到新版本时先以待确认候选呈现，确认后才能一键升级。")));

        GroundingOutcome outcome = service.assemble(7L, "升级中心检测到新版本后怎么升级");

        assertFalse(outcome.zeroHit());
        assertEquals("help", outcome.citations().get(0).kind());
        assertEquals(120L, outcome.citations().get(0).refId());
        assertEquals("AF", outcome.citations().get(0).domainCode());
        assertTrue(outcome.contextBlock().startsWith("[C1]"), outcome.contextBlock());
    }

    @Test
    void knowledgeChunksFeedCitationsWithinProjectScopeAndBusinessScene() {
        when(gateway.listHelpDocs()).thenReturn(List.of());
        when(searchService.search(any())).thenReturn(List.of(new KnowledgeSearchItem(
                "chunk-1", 77L, 0L, "SPEC", "回滚方案",
                "回滚按反序执行波次，失败时秒级切回旧实例。", 0.81, 0.81)));

        GroundingOutcome outcome = service.assemble(7L, "回滚方案是什么");

        assertEquals(77L, outcome.citations().get(0).refId());
        assertEquals(GroundingKernel.KIND_KNOWLEDGE, outcome.citations().get(0).kind());
        ArgumentCaptor<KnowledgeSearchRequest> request =
                ArgumentCaptor.forClass(KnowledgeSearchRequest.class);
        verify(searchService).search(request.capture());
        assertEquals(7L, request.getValue().projectId());
        assertEquals("BUSINESS", request.getValue().scene());
    }

    @Test
    void brokenRetrievalDegradesToHonestZeroHitInsteadOfFailingTheTurn() {
        when(gateway.listHelpDocs()).thenThrow(new RuntimeException("core down"));
        when(searchService.search(any())).thenThrow(new RuntimeException("vector store down"));

        GroundingOutcome outcome = service.assemble(7L, "升级中心怎么升级");

        assertTrue(outcome.zeroHit());
        assertTrue(outcome.citations().isEmpty());
        assertEquals("", outcome.contextBlock());
    }

    @Test
    void twoChunksOfTheSameKnowledgeDocCollapseToOneCitation() {
        when(gateway.listHelpDocs()).thenReturn(List.of());
        when(searchService.search(any())).thenReturn(List.of(
                new KnowledgeSearchItem("c1", 55L, 0L, "SPEC", "批次执行手册",
                        "批次执行的第一段。", 0.72, 0.72),
                new KnowledgeSearchItem("c2", 55L, 0L, "SPEC", "批次执行手册",
                        "批次执行的第二段，包含更多批次执行细节。", 0.85, 0.85)));

        GroundingOutcome outcome = service.assemble(7L, "批次执行细节有哪些");

        assertEquals(1, outcome.citations().size());
        assertEquals(55L, outcome.citations().get(0).refId());
    }

    @Test
    void k8BudgetKeysOverrideSeededDefaults() {
        when(gateway.listHelpDocs()).thenReturn(List.of());
        when(gateway.getSysConfig(GroundingService.KEY_MAX_CITATIONS)).thenReturn("1");
        when(gateway.getHelpDomainChapters(anyString())).thenReturn(List.of());
        when(searchService.search(any())).thenReturn(List.of(
                new KnowledgeSearchItem("c1", 11L, 0L, "SPEC", "回滚方案甲", "内容甲。", 0.9, 0.9),
                new KnowledgeSearchItem("c2", 22L, 0L, "SPEC", "回滚方案乙", "内容乙。", 0.8, 0.8)));

        GroundingOutcome outcome = service.assemble(7L, "回滚方案是什么");

        assertEquals(1, outcome.citations().size());
    }

    @Test
    void blankQuestionNeverTouchesTheSources() {
        GroundingOutcome outcome = service.assemble(7L, "   ");

        assertTrue(outcome.zeroHit());
        verify(gateway, never()).listHelpDocs();
        verify(searchService, never()).search(any());
        verify(gateway, never()).getSysConfig(anyString());
    }

    /**
 * Ledger revision rides the citation : knowledge hits are
 * enriched with the ledger's current revision so the cite frame can name
 * which version of the document backed the answer.
 */
    @Test
    void knowledgeCitationsCarryLedgerRevision() {
        when(gateway.listHelpDocs()).thenReturn(List.of());
        when(searchService.search(any())).thenReturn(List.of(new KnowledgeSearchItem(
                "chunk-9", 91L, 0L, "SPEC", "水位告警口径", "上下文水位按 token 计。", 0.9, 0.9)));
        when(ledgerJdbc.queryForList(anyString(),
                any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(java.util.Map.of("id", 91L, "revision", 4)));

        GroundingOutcome outcome = service.assemble(7L, "上下文水位怎么算");

        assertEquals(GroundingKernel.KIND_KNOWLEDGE, outcome.citations().get(0).kind());
        assertEquals(4, outcome.citations().get(0).revision(),
                "knowledge citation must carry the ledger revision");
    }

    /**
 * Defect corpus rides the grounding pool as a live source (AO batch
 * S4 : the newest defects of the asking project become
 * {@code defect} citations, and the row's attribution facts (who fixes it,
 * why it was closed) ride only on the manager-only shape — a plain member
 * still cites the defect, just without the responsibility text.
 */
    @Test
    void defectRowsBecomeCitationsAndAttributionStaysManagerOnly() {
        when(gateway.listHelpDocs()).thenReturn(List.of());
        when(searchService.search(any())).thenReturn(List.of());
        when(ledgerJdbc.queryForList(org.mockito.ArgumentMatchers.contains("tp_defect"),
                any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenReturn(List.of(defectRow(3021L, "登录页在并发下超时",
                        "登录接口在 200 并发下平均响应超过 8 秒。", 2, 5, "张三", "重复缺陷关闭")));

        GroundingOutcome asManager = service.assemble(7L, "登录页超时 修复人 关闭原因", true);
        assertEquals("defect", asManager.citations().get(0).kind());
        assertEquals(3021L, asManager.citations().get(0).refId());
        assertTrue(asManager.contextBlock().contains("张三"),
                "the manager view keeps the responsibility fact");

        GroundingOutcome asMember = service.assemble(7L, "登录页超时 修复人 关闭原因", false);
        assertEquals("defect", asMember.citations().get(0).kind(),
                "the defect history itself stays citable for a member");
        assertFalse(asMember.contextBlock().contains("张三"),
                "responsibility facts never reach a member run");
        assertFalse(asMember.contextBlock().contains("重复缺陷关闭"),
                "nor the closing attribution");

        ArgumentCaptor<org.springframework.jdbc.core.namedparam.MapSqlParameterSource> params =
                ArgumentCaptor.forClass(
                        org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class);
        verify(ledgerJdbc, org.mockito.Mockito.atLeastOnce())
                .queryForList(org.mockito.ArgumentMatchers.contains("tp_defect"), params.capture());
        assertEquals(7L, params.getValue().getValue("projectId"),
                "the corpus stays inside the asking project slice (H1)");
    }

    /** A defect-table outage drops that source only — the turn still stands on the others. */
    @Test
    void defectCorpusOutageDegradesFailSoftWithoutFailingTheTurn() {
        when(gateway.listHelpDocs()).thenReturn(List.of(chapter(120, "AF", "升级中心：平台版本与一键升级")));
        when(gateway.getHelpDomainChapters("AF")).thenReturn(List.of(
                fullChapter(120, "AF", "升级中心：平台版本与一键升级",
                        "升级中心展示当前平台版本，检测到新版本时先以待确认候选呈现。")));
        when(searchService.search(any())).thenReturn(List.of());
        when(ledgerJdbc.queryForList(org.mockito.ArgumentMatchers.contains("tp_defect"),
                any(org.springframework.jdbc.core.namedparam.MapSqlParameterSource.class)))
                .thenThrow(new RuntimeException("defect table unreachable"));

        GroundingOutcome outcome = service.assemble(7L, "升级中心检测到新版本后怎么升级", false);

        assertFalse(outcome.zeroHit(), "a broken defect source must not fail the turn");
        assertEquals("help", outcome.citations().get(0).kind());
    }

    /** One tp_defect row as the corpus reader sees it (HashMap allows null attribution columns). */
    private static java.util.Map<String, Object> defectRow(long id, String title, String description,
                                                            int severity, int status,
                                                            String assigneeName, String closedReason) {
        java.util.Map<String, Object> row = new java.util.HashMap<>();
        row.put("id", id);
        row.put("title", title);
        row.put("description", description);
        row.put("severity", severity);
        row.put("status", status);
        row.put("assignee_name", assigneeName);
        row.put("closed_reason", closedReason);
        return row;
    }
}
