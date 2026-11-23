package com.sixbean.testpike.core.aichat.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.aichat.api.dto.ChatContextWaterlineView;
import com.sixbean.testpike.core.aichat.api.dto.ChatSessionInfo;
import com.sixbean.testpike.core.aichat.domain.service.ChatSessionService;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Context waterline read door : the numbers behind the
 * panel bar come from the sealed message ledger and the K8 config, never from
 * a client-side guess. A session the caller does not own must be refused
 * before any read happens (AF-g horizontal posture).
 * <p>L2 posture: {@code NamedParameterJdbcTemplate} stands in for the DB read
 * only (the aggregate SQL's row contract is what is asserted); services,
 * config semantics and the fold arithmetic are the real classes.
 */
class ContextWaterlineReadTest {

    private static final String KEY_BUDGET = "ai.chat.session-budget-tokens";
    private static final String KEY_KEEP_RECENT = "ai.ctx.session-keep-recent-turns";

    private NamedParameterJdbcTemplate named;
    private ConfigService configService;
    private ChatSessionService sessionService;
    private ChatContextWaterlineServiceImpl service;

    @BeforeEach
    void setUp() {
        named = mock(NamedParameterJdbcTemplate.class);
        configService = mock(ConfigService.class);
        sessionService = mock(ChatSessionService.class);
        service = new ChatContextWaterlineServiceImpl(named, configService, sessionService,
                new ObjectMapper());
    }

    private void ledger(long promptTokensUsed, long userTurns) {
        when(named.queryForMap(anyString(), any(Map.class)))
                .thenReturn(Map.of("prompt_tokens_used", promptTokensUsed, "user_turns", userTurns));
    }

    private void ownedSession(String contextJson) {
        when(sessionService.requireOwned(7L, 9L)).thenReturn(new ChatSessionInfo(
                7L, 100L, 9L, "水位断点", 2, contextJson, 0, 40L, 123456L));
    }

    @Test
    void numeratorAndFoldComeFromTheSealedLedger() {
        ledger(123456L, 20L);
        ownedSession("{\"route\":\"/cases\",\"projectId\":7}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("2000000");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("6");

        ChatContextWaterlineView view = service.waterline(7L, 9L);

        assertEquals(123456L, view.promptTokensUsed(), "numerator = SUM(prompt_tokens) of the ledger");
        assertEquals(2000000L, view.budgetTokens());
        assertFalse(view.budgetUnlimited());
        assertEquals(20L, view.sealedUserTurns());
        assertEquals(6, view.keepRecentTurns());
        assertEquals(14L, view.foldedTurns(), "20 sealed turns keeping the newest 6 raw folds 14");
        assertEquals(0, view.pinnedFactsCount(),
                "route + projectId are the navigation envelope, never pinned facts");
    }

    @Test
    void envelopeKeysAreSkippedButObjectPointersCountAsFacts() {
        ledger(10L, 1L);
        ownedSession("{\"route\":\"/ai/chat\",\"projectId\":7,\"objectId\":42,\"documentId\":8}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("200000");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("6");

        ChatContextWaterlineView view = service.waterline(7L, 9L);

        assertEquals(2, view.pinnedFactsCount(),
                "object/document pointers are pinned context; route/projectId are not");
    }

    @Test
    void unparsableBudgetReadsAsHonestUnlimited() {
        ledger(500L, 3L);
        ownedSession("{}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("abc");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("6");

        ChatContextWaterlineView view = service.waterline(7L, 9L);

        assertTrue(view.budgetUnlimited(), "garbage in the config box must not fake a cap");
        assertEquals(0L, view.budgetTokens(), "no usable cap reads as zero, never as 'budget used up'");
        assertEquals(500L, view.promptTokensUsed());
    }

    @Test
    void negativeBudgetReadsAsHonestUnlimited() {
        ledger(500L, 3L);
        ownedSession("{}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("-1");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("6");

        assertTrue(service.waterline(7L, 9L).budgetUnlimited(),
                "a non-positive cap disables the ceiling instead of blocking the dialog");
    }

    @Test
    void keepRecentOutsideTheWriteGuardIntervalIsClamped() {
        ledger(500L, 20L);
        ownedSession("{}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("2000000");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("999");

        ChatContextWaterlineView view = service.waterline(7L, 9L);

        assertEquals(50, view.keepRecentTurns(), "read clamps against AiCtxKeyRanges, no second interval table");
        assertEquals(0L, view.foldedTurns(), "keeping 50 of 20 turns folds nothing");
    }

    @Test
    void nestedContextEntriesNeverCountAsPinnedFacts() {
        ledger(500L, 2L);
        ownedSession("{\"route\":\"/cases\",\"objectId\":5,\"payload\":{\"a\":1}}");
        when(configService.getValue(eq(KEY_BUDGET), anyString())).thenReturn("2000000");
        when(configService.getValue(eq(KEY_KEEP_RECENT), anyString())).thenReturn("6");

        assertEquals(1, service.waterline(7L, 9L).pinnedFactsCount(),
                "only scalar facts count; a nested block is not a pinned fact");
    }

    @Test
    void strangerRefusedBeforeAnyLedgerRead() {
        when(sessionService.requireOwned(7L, 424L))
                .thenThrow(new ServiceException("该对话不属于当前用户", 40301));

        assertThrows(ServiceException.class, () -> service.waterline(7L, 424L));

        verify(named, never()).queryForMap(anyString(), any(Map.class));
        verify(configService, never()).getValue(anyString(), anyString());
    }
}
