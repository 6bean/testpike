package com.sixbean.testpike.aiservice.aictx.domain.service.impl;

import com.sixbean.testpike.aiservice.aictx.infrastructure.remote.SystemDraftGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Incremental correction-distillation contract :
 * four feedback sources are read through last-id watermarks, distilled per
 * project by the LLM chain into project-memory entries, and a source's
 * watermark only advances after its batch fully succeeded.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CtxDistillServiceImplTest {

    @Mock
    private NamedParameterJdbcTemplate jdbc;

    @Mock
    private LlmFallbackChain llmChain;

    @Mock
    private SystemDraftGateway systemDraftGateway;

    @Mock
    private com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway coreGateway;

    private CtxDistillServiceImpl underTest;
    private CtxDistillServiceImpl spyUnderTest;

    @BeforeEach
    void setUp() {
        underTest = new CtxDistillServiceImpl(jdbc, llmChain, systemDraftGateway, coreGateway);
        spyUnderTest = spy(underTest);
    }

    @Test
    void emptySourcesMakeNoLlmCallAndAdvanceNoWatermark() {
        for (String source : CtxDistillServiceImpl.SOURCES) {
            doReturn(List.of()).when(spyUnderTest).readSource(eq(source), anyLong());
        }
        int created = spyUnderTest.distillOnce();
        assertEquals(0, created);
        verifyNoInteractions(llmChain);
        for (String source : CtxDistillServiceImpl.SOURCES) {
            verify(spyUnderTest, never()).advanceWatermark(eq(source), anyLong());
        }
    }

    @Test
    void downvoteBatchIsDistilledIntoMemoryEntriesWithSourceAnchorsThenAdvanced() {
        stubAllSourcesEmptyExcept(CtxDistillServiceImpl.SOURCE_CHAT_DOWNVOTE,
                List.of(memoryRow(101, 7, "断言写错被点踩"),
                        memoryRow(102, 7, "分页漏测被点踩")));
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenReturn(chatResult("- 接口断言必须校验响应体\n- 列表用例必须覆盖分页与空集合\n"));
        when(jdbc.update(anyString(), anyMap())).thenReturn(1);
        when(jdbc.queryForObject(contains("tp_ai_ctx_memory"), anyMap(), eq(Long.class)))
                .thenReturn(42L);

        int created = spyUnderTest.distillOnce();

        assertEquals(2, created);
        ArgumentCaptor<String> contents = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> refs = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Long> targets = ArgumentCaptor.forClass(Long.class);
        verify(systemDraftGateway, org.mockito.Mockito.times(2))
                .postSystemDraft(eq(7L), contents.capture(), refs.capture(), targets.capture());
        assertEquals("接口断言必须校验响应体", contents.getAllValues().get(0));
        assertEquals(42L, targets.getAllValues().get(0).longValue(),
                "the card must carry the freshly-created awaiting row id so the ledger can anchor it (slice 4c)");
        String ref = refs.getAllValues().get(0);
        assertTrue(ref.contains(CtxDistillServiceImpl.SOURCE_CHAT_DOWNVOTE)
                        && ref.contains("101") && ref.contains("102"),
                "source_ref must anchor every distilled source row, got: " + ref);
        verify(spyUnderTest).advanceWatermark(CtxDistillServiceImpl.SOURCE_CHAT_DOWNVOTE, 102L);
    }

    @Test
    void llmFailureKeepsThatSourcesWatermarkUnadvancedButOtherSourcesStillRun() {
        stubAllSourcesEmptyExcept(CtxDistillServiceImpl.SOURCE_CHAT_DOWNVOTE,
                List.of(memoryRow(201, 9, "拒答反馈")));
        when(llmChain.chat(any(ChatCompletionRequest.class), anyLong(), any()))
                .thenThrow(new IllegalStateException("provider down"));

        int created = spyUnderTest.distillOnce();

        assertEquals(0, created);
        verifyNoInteractions(systemDraftGateway);
        verify(jdbc, never()).update(contains("tp_ai_ctx_memory"), anyMap());
        verify(spyUnderTest, never()).advanceWatermark(
                eq(CtxDistillServiceImpl.SOURCE_CHAT_DOWNVOTE), anyLong());
    }

    @Test
    void watermarksLiveUnderDedicatedConfigKeysPerSource() {
        when(jdbc.queryForList(contains("tp_sys_config"), anyMap()))
                .thenReturn(List.of(Map.of("config_value", "500")));
        long value = underTest.readWatermark(CtxDistillServiceImpl.SOURCE_REVIEW_REJECT);
        assertEquals(500L, value);
        ArgumentCaptor<Map<String, Object>> read = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).queryForList(contains("tp_sys_config"), read.capture());
        assertEquals(CtxDistillServiceImpl.WATERMARK_KEY_PREFIX + "review_reject",
                read.getValue().get("key"));

        when(jdbc.update(anyString(), anyMap())).thenReturn(1);
        underTest.advanceWatermark(CtxDistillServiceImpl.SOURCE_STEP_HEALING, 880L);
        ArgumentCaptor<Map<String, Object>> write = ArgumentCaptor.forClass(Map.class);
        verify(jdbc).update(contains("tp_sys_config"), write.capture());
        assertEquals(CtxDistillServiceImpl.WATERMARK_KEY_PREFIX + "step_healing",
                write.getValue().get("key"));
    }

    /**
     * Every source query must ship an executable LIMIT clause: Java strips the
     * trailing space before a text-block closing delimiter, so concatenating
     * the batch size onto "LIMIT" produced "LIMIT200" and all four distillation
     * sources failed with bad SQL grammar on every scheduler pass.
     */
    @Test
    void everySourceQueryBindsTheBatchSizeInsteadOfConcatenatingItOntoLimit() {
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        for (String source : CtxDistillServiceImpl.SOURCES) {
            underTest.readSource(source, 0L);
        }
        verify(jdbc, times(CtxDistillServiceImpl.SOURCES.size()))
                .queryForList(sql.capture(), params.capture());
        for (int i = 0; i < sql.getAllValues().size(); i++) {
            String statement = sql.getAllValues().get(i);
            Map<String, Object> bind = params.getAllValues().get(i);
            assertTrue(statement.matches("(?s).*LIMIT\\s+:batchLimit.*"),
                    "limit must be a bound parameter separated from LIMIT, got: " + statement);
            assertEquals(CtxDistillServiceImpl.BATCH_LIMIT,
                    bind.get("batchLimit"),
                    "bound batch size must match the source constant");
        }
    }

    private void stubAllSourcesEmptyExcept(String source, List<Map<String, Object>> rows) {
        for (String s : CtxDistillServiceImpl.SOURCES) {
            doReturn(s.equals(source) ? rows : List.of())
                    .when(spyUnderTest).readSource(eq(s), anyLong());
        }
    }

    private static Map<String, Object> memoryRow(long id, long projectId, String detail) {
        return Map.of("id", id, "projectId", projectId, "detail", detail);
    }

    private static LlmChatResult chatResult(String content) {
        return new LlmChatResult(content, "mock", "m", 10, 20, 5L);
    }
}
