package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Capability prober contract (ticket-② ledger promotion channel): the probe
 * must reproduce the tools_schema wire shape (schema block AND a small tool
 * list riding the same request — the exact combination the ledger row marks
 * as accepted-without-enforce), judge enforcement by the presence of the
 * schema's required keys in the returned content, and answer in three states:
 * PROMOTED / STILL_UNENFORCED / UNKNOWN. An unreachable or mock endpoint may
 * never promote or demote a row (UNKNOWN writes nothing) — P18 three-state
 * discipline: absence of evidence is not evidence of absence.
 */
class LlmCapabilityProberTest {

    private final LlmProviderFactory factory = mock(LlmProviderFactory.class);
    private final org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate jdbc =
            mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.class);
    private final LlmOutputCapabilityLedger ledger = new LlmOutputCapabilityLedger(jdbc);
    private final LlmCapabilityProber prober =
            new LlmCapabilityProber(factory, ledger, new ObjectMapper());

    private static LlmEndpointConfig endpoint() {
        return new LlmEndpointConfig("openai", "probe-model", "k", "http://base", "project");
    }

    @Test
    void 探测报文复刻tools形态_结构化块与小工具清单同挂() {
        when(factory.isMock()).thenReturn(false);
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult("{\"probe_status\":\"ok\"}", "openai",
                        "probe-model", 10, 5, 20));

        LlmProbeVerdict verdict = prober.probeAndRecord(endpoint(),
                LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        assertEquals(LlmProbeVerdict.PROMOTED, verdict);
        ArgumentCaptor<ChatCompletionRequest> sent =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(factory).chatFor(sent.capture(), any(LlmEndpointConfig.class));
        assertNotNull(sent.getValue().responseFormat(),
                "the probe must mount the schema block — that is the thing under test");
        assertNotNull(sent.getValue().responseFormat().jsonSchema(),
                "probe rides the json_schema flavour, not bare json_object");
        List<?> tools = sent.getValue().tools();
        assertNotNull(tools, "tools_schema form probe must carry a tool list on the same request");
        assertFalse(tools.isEmpty(), "empty tool list would not reproduce the co-existence shape");
    }

    @Test
    void 输出含全部必填键_转正落库并失权缓存() {
        when(factory.isMock()).thenReturn(false);
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult("{\"probe_status\":\"ok\"}", "openai",
                        "probe-model", 10, 5, 20));
        // Pre-seed the unenforced truth so the cache invalidation is observable.
        ledger.observeNotEnforced("probe-model", LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        prober.probeAndRecord(endpoint(), LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        verify(jdbc).update(eq("UPDATE tp_llm_output_capability SET enforced = 1,"
                        + " last_probed_at = now(), updated_at = now()"
                        + " WHERE model_name = :model AND call_form = :form"),
                any(MapSqlParameterSource.class));
        assertFalse(ledger.isFormUnenforced("probe-model",
                        LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA),
                "a promoted row must stop steering calls away — cache lost its grip, door says enforced");
    }

    @Test
    void 输出缺必填键_维持不强制_仅刷新探测时刻() {
        when(factory.isMock()).thenReturn(false);
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult("sure, here is some prose instead", "openai",
                        "probe-model", 10, 5, 20));

        LlmProbeVerdict verdict = prober.probeAndRecord(endpoint(),
                LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        assertEquals(LlmProbeVerdict.STILL_UNENFORCED, verdict);
        verify(jdbc, never()).update(eq("UPDATE tp_llm_output_capability SET enforced = 1,"
                + " last_probed_at = now(), updated_at = now()"
                + " WHERE model_name = :model AND call_form = :form"),
                any(MapSqlParameterSource.class));
        verify(jdbc).update(eq("UPDATE tp_llm_output_capability"
                        + " SET last_probed_at = now(), updated_at = now()"
                        + " WHERE model_name = :model AND call_form = :form"),
                any(MapSqlParameterSource.class));
    }

    @Test
    void 端点不可达_UNKNOWN_台账零触碰() {
        when(factory.isMock()).thenReturn(false);
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenThrow(new RuntimeException("connection refused"));

        LlmProbeVerdict verdict = prober.probeAndRecord(endpoint(),
                LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        assertEquals(LlmProbeVerdict.UNKNOWN, verdict);
        verify(jdbc, never()).update(anyString(), any(MapSqlParameterSource.class));
    }

    @Test
    void 替身端点_UNKNOWN_不产生任何证明() {
        when(factory.isMock()).thenReturn(true);

        LlmProbeVerdict verdict = prober.probeAndRecord(endpoint(),
                LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        assertEquals(LlmProbeVerdict.UNKNOWN, verdict);
        verify(factory, never()).chatFor(any(ChatCompletionRequest.class),
                any(LlmEndpointConfig.class));
    }

    @Test
    void 模型改走工具调用而无正文_UNKNOWN() {
        when(factory.isMock()).thenReturn(false);
        when(factory.chatFor(any(ChatCompletionRequest.class), any(LlmEndpointConfig.class)))
                .thenReturn(new LlmChatResult(null, "openai", "probe-model", 10, 0, 20,
                        List.of(new com.sixbean.testpike.aiservice.llm.domain.LlmToolCall(
                                "call-1", "capability_probe_ping", "{}"))));

        LlmProbeVerdict verdict = prober.probeAndRecord(endpoint(),
                        LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA);

        assertEquals(LlmProbeVerdict.UNKNOWN, verdict,
                "no content to judge = no evidence; must not be read as unenforced");
    }
}
