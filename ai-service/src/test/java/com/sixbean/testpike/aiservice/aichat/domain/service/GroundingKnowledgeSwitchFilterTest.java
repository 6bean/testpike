package com.sixbean.testpike.aiservice.aichat.domain.service;

import com.sixbean.testpike.aiservice.aichat.domain.component.GroundingOutcome;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.knowledge.api.dto.KnowledgeSearchItem;
import com.sixbean.testpike.aiservice.knowledge.domain.service.KnowledgeSearchService;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Grounding-side usage switch decision : the dialog grounding
 * assembly asks {@code use_in_grounding} exactly once, over the shared
 * DocumentUsageSwitchReader, at this consumption point. A document whose
 * grounding switch is off never becomes a citation, while an unreachable
 * ledger or a chunk without a tp_document row keeps the historical all-on
 * behaviour (fail-soft, zero drift for existing TEST_SPEC corpora).
 * <p>Core gateway / vector search / ledger JDBC are mocked at L2 per
 * protocol; the live grounding chain is exercised by the L3 combo segment.
 */
class GroundingKnowledgeSwitchFilterTest {

    private final CoreGateway gateway = mock(CoreGateway.class);
    private final KnowledgeSearchService searchService = mock(KnowledgeSearchService.class);
    private final NamedParameterJdbcTemplate ledgerJdbc = mock(NamedParameterJdbcTemplate.class);
    private final GroundingService service = new GroundingService(gateway, searchService, ledgerJdbc);

    private static KnowledgeSearchItem chunk(String id, long docId) {
        return new KnowledgeSearchItem(id, docId, 0L, "TEST_SPEC", "参考手册章节",
                "参考手册正文：接口命名约定。", 0.9, 0.9);
    }

    /** Stubs only the usage-switch query; every other ledger read stays empty. */
    private void stubSwitchRows(List<Map<String, Object>> rows) {
        when(ledgerJdbc.queryForList(contains("use_in_grounding"), any(MapSqlParameterSource.class)))
                .thenReturn(rows);
    }

    @Test
    void groundingSwitchOffExcludesTheDocumentFromCitations() {
        when(searchService.search(any())).thenReturn(List.of(chunk("c1", 555L)));
        stubSwitchRows(List.of(Map.of("id", 555L, "opted", false)));

        GroundingOutcome outcome = service.assemble(7L, "接口命名约定是什么");

        assertTrue(outcome.zeroHit(),
                "「参与检索接地」关闭的文档禁成为接地引用: " + outcome.citations());
    }

    @Test
    void ledgerWithoutSwitchOffRowKeepsTheDocumentCitable() {
        when(searchService.search(any())).thenReturn(List.of(chunk("c1", 555L)));
        stubSwitchRows(List.of());

        GroundingOutcome outcome = service.assemble(7L, "接口命名约定是什么");

        assertFalse(outcome.zeroHit(), "无关闭记录=默认开（存量零漂移）");
        assertTrue(outcome.citations().stream().anyMatch(c -> "knowledge".equals(c.kind())),
                outcome.citations().toString());
    }
}
