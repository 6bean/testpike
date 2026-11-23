package com.sixbean.testpike.aiservice.aictx.domain.component;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Hit-count writer contract : one parameterized
 * UPDATE bumps hit_count for every injected memory id in a single statement
 * (no per-id round trips); null/empty input never touches the database.
 * The aictx domain owns tp_ai_ctx_memory writes (CtxDistillServiceImpl —
 * same-domain single source); the assembly read side lives in core and stays
 * read-only.
 */
class CtxMemoryHitWriterTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final CtxMemoryHitWriter underTest = new CtxMemoryHitWriter(jdbc);

    @Test
    void 命中批量自增单语句() {
        when(jdbc.update(anyString(), (MapSqlParameterSource) org.mockito.ArgumentMatchers.any()))
                .thenReturn(2);

        int updated = underTest.recordHits(List.of(5L, 6L));

        assertEquals(2, updated);
        ArgumentCaptor<MapSqlParameterSource> cap =
                ArgumentCaptor.forClass(MapSqlParameterSource.class);
        verify(jdbc).update(contains("hit_count = hit_count + 1"), cap.capture());
        assertEquals(List.of(5L, 6L), cap.getValue().getValue("ids"), ":ids 集合参数单语句展开");
    }

    @Test
    void 空入参零触库() {
        underTest.recordHits(List.of());
        underTest.recordHits(null);
        verifyNoInteractions(jdbc);
    }
}
