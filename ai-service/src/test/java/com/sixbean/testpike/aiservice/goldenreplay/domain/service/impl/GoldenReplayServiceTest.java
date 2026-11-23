package com.sixbean.testpike.aiservice.goldenreplay.domain.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeCoreGateway;
import com.sixbean.testpike.aiservice.judge.infrastructure.remote.JudgeSampleBrief;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.domain.LlmChatResult;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmFallbackChain;
import com.sixbean.testpike.aiservice.llm.domain.component.ProjectLlmRouter;
import com.sixbean.testpike.aiservice.llm.pipeline.BehaviorKernelAssembly;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenHeadReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenReplayRequest;
import com.sixbean.testpike.aiservice.goldenreplay.api.dto.GoldenRouteReplayRequest;
import com.sixbean.testpike.common.exception.ValidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Golden replay executor contract (drift comparison, outer half of the
 * golden-set item): the domain's golden pack (judge eval-set) is replayed
 * through the control and the candidate template version of one pinned model;
 * every output lands on the core drift ledger as a normalized digest, never
 * as a self-declared pass rate. Refusal paths must name the mapping key and
 * where to fix it, and must never touch the model.
 */
class GoldenReplayServiceTest {

    private static final String MAPPING_KEY = "ai.behavior.golden-pack.mapping";
    private static final String MAPPING_JSON =
            "{\"case-gen\":{\"setName\":\"golden-case-gen\",\"version\":3}}";

    private CoreGateway coreGateway;
    private JudgeCoreGateway judgeCoreGateway;
    private LlmFallbackChain llmFallbackChain;
    private ProjectLlmRouter router;
    private GoldenReplayServiceImpl service;

    @BeforeEach
    void setUp() {
        coreGateway = mock(CoreGateway.class);
        judgeCoreGateway = mock(JudgeCoreGateway.class);
        llmFallbackChain = mock(LlmFallbackChain.class);
        router = mock(ProjectLlmRouter.class);
        service = new GoldenReplayServiceImpl(coreGateway, judgeCoreGateway,
                llmFallbackChain, router, new ObjectMapper());
        when(coreGateway.getSysConfig(MAPPING_KEY)).thenReturn(MAPPING_JSON);
        when(judgeCoreGateway.listSamples("golden-case-gen", 3)).thenReturn(List.of(
                new JudgeSampleBrief(11L, "input one", "expected one", List.of(), 0),
                new JudgeSampleBrief(12L, "input two", "expected two", List.of(), 0)));
        when(coreGateway.promptTemplateDetail(100L))
                .thenReturn(new CoreGateway.PromptTemplateBrief(100L, "case-gen-prompt",
                        "case-gen", 7, "control template body", 2));
        when(coreGateway.promptTemplateDetail(200L))
                .thenReturn(new CoreGateway.PromptTemplateBrief(200L, "case-gen-prompt",
                        "case-gen", 8, "candidate template body", 1));
    }

    private GoldenReplayRequest request(Long controlId) {
        return new GoldenReplayRequest("case-gen", "case-gen-prompt", 200L, controlId, 1L, "glm-4.6");
    }

    private LlmChatResult out(String text) {
        return new LlmChatResult(text, "p", "glm-4.6", 1, 1, 1L);
    }

    @Test
    void benignReplayPostsTwoPairsWithNormalizedDigestsAndPackSnapshot() {
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("same answer"))
                .thenReturn(out(" same  answer \n"))
                .thenReturn(out("other"))
                .thenReturn(out("other \t"));
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        var result = service.replay(request(100L));

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(coreGateway).postGoldenDriftVerdict(body.capture());
        Map<String, Object> sent = body.getValue();
        assertEquals("case-gen", sent.get("templateDomain"));
        assertEquals(200L, sent.get("candidateVersionId"));
        assertEquals(100L, sent.get("controlVersionId"));
        // The template axis keeps the legacy body shape: no axis fields.
        assertFalse(sent.containsKey("comparisonKind"), "template axis must not carry axis fields");
        assertFalse(sent.containsKey("controlRef"));
        assertFalse(sent.containsKey("candidateRef"));
        assertEquals("golden-case-gen", sent.get("packName"));
        assertEquals(3, sent.get("packVersion"));
        String digest = (String) sent.get("snapshotDigest");
        assertTrue(digest.matches("[0-9a-f]{64}"), "pack snapshot must be sha-256 hex");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) sent.get("pairs");
        assertEquals(2, pairs.size());
        assertEquals("11", pairs.get(0).get("sampleKey").toString());
        // whitespace-only difference = not drifted; case difference = drifted
        assertEquals(pairs.get(0).get("controlDigest"), pairs.get(0).get("candidateDigest"));
        assertTrue(pairs.get(0).get("controlDigest").toString().matches("[0-9a-f]{64}"));
        assertEquals(0, result.driftedCount());
        assertEquals(2, result.sampleTotal());
        // same pinned model on both sides for every sample (control-variable replay)
        verify(llmFallbackChain, times(4))
                .chat(any(ChatCompletionRequest.class), eq(1L), any(), eq(true));
    }

    @Test
    void driftedSampleIsCountedAndAllPairsStillLand() {
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("a"))      // s11 control
                .thenReturn(out("b"))      // s11 candidate -> drifted
                .thenReturn(out("c"))      // s12 control
                .thenReturn(out("c"));     // s12 candidate -> stable
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        var result = service.replay(request(100L));
        assertEquals(1, result.driftedCount());
        assertEquals(2, result.sampleTotal());
    }

    @Test
    void missingDomainMappingRefusesNamingKeyAndPlaceAndNeverCallsModel() {
        when(coreGateway.getSysConfig(MAPPING_KEY))
                .thenReturn("{\"other\":{\"setName\":\"x\",\"version\":1}}");
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.replay(request(100L)));
        assertTrue(error.getMessage().contains(MAPPING_KEY), "must name the config key");
        assertTrue(error.getMessage().contains("系统设置"), "must point at where to open it");
        verify(llmFallbackChain, never())
                .chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean());
        verify(coreGateway, never()).postGoldenDriftVerdict(any());
    }

    @Test
    void emptyGoldenPackRefusesBeforeTouchingTheModel() {
        when(judgeCoreGateway.listSamples(anyString(), anyInt())).thenReturn(List.of());
        assertThrows(ValidationException.class, () -> service.replay(request(100L)));
        verify(llmFallbackChain, never())
                .chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean());
    }

    @Test
    void absentControlResolvesTheCurrentFullReleaseRow() {
        when(coreGateway.promptTemplateLatestByStatus("case-gen-prompt", "case-gen", 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(100L, "case-gen-prompt",
                        "case-gen", 7, "control template body", 2));
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("x"));
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        service.replay(request(null));
        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(coreGateway).postGoldenDriftVerdict(body.capture());
        assertEquals(100L, body.getValue().get("controlVersionId"));
    }

    @Test
    void candidateEqualToControlIsRefusedAsAMisuse() {
        assertThrows(ValidationException.class, () -> service.replay(request(200L)));
    }

    @Test
    void replayHeadsCarryTheKernelInvariantOnceAheadOfTheVersionBody() {
        // The replay must mirror production head shape (AX round-2 ledger
        // wiring): the invariant clause leads exactly once and the replayed
        // version's own template body follows, so the drift verdict compares
        // what real traffic would actually send, not a bare body.
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("same answer"));
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        service.replay(request(100L));

        ArgumentCaptor<ChatCompletionRequest> captor =
                ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain, times(4))
                .chat(captor.capture(), eq(1L), any(), eq(true));
        Set<String> heads = new HashSet<>();
        for (ChatCompletionRequest sent : captor.getAllValues()) {
            String head = sent.messages().get(0).content();
            assertEquals(1, BehaviorKernelAssembly.kernelClauseCount(head),
                    "each replayed head carries the kernel invariant exactly once");
            assertTrue(head.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE),
                    "the invariant leads the head (shared cache prefix, same as production)");
            heads.add(head);
        }
        assertEquals(2, heads.size(), "control and candidate bodies stay distinct behind the shared prefix");
        assertTrue(heads.stream().anyMatch(h -> h.contains("control template body")),
                "control arm keeps its own template body");
        assertTrue(heads.stream().anyMatch(h -> h.contains("candidate template body")),
                "candidate arm keeps its own template body");
    }

    // ---- route axis (same template, two model routes) ----

    private GoldenRouteReplayRequest routeRequest(String control, String candidate) {
        return new GoldenRouteReplayRequest("case-gen", "case-gen-prompt", control, candidate, 1L);
    }

    private void stubReleasedFamilyRow() {
        when(coreGateway.promptTemplateLatestByStatus("case-gen-prompt", "case-gen", 2))
                .thenReturn(new CoreGateway.PromptTemplateBrief(100L, "case-gen-prompt",
                        "case-gen", 7, "released template body", 2));
    }

    @Test
    void routeReplayRunsOneReleasedTemplateThroughTwoModelRoutesAndLandsRouteAxis() {
        stubReleasedFamilyRow();
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("a1"))   // s11 control model
                .thenReturn(out("a1"))   // s11 candidate model -> stable
                .thenReturn(out("b1"))   // s12 control model
                .thenReturn(out("b2"));  // s12 candidate model -> drifted
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        var result = service.replayRouteSwitch(routeRequest("glm-4.6", "glm-5.0"));

        ArgumentCaptor<ChatCompletionRequest> calls = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain, times(4)).chat(calls.capture(), eq(1L), any(), eq(true));
        List<String> models = new ArrayList<>();
        calls.getAllValues().forEach(c -> models.add(c.model()));
        assertEquals(List.of("glm-4.6", "glm-5.0", "glm-4.6", "glm-5.0"), models,
                "each sample runs control model first, then candidate model, alternating per sample");
        // the template head is the control variable: every call sends the same head
        Set<String> heads = new HashSet<>();
        calls.getAllValues().forEach(c -> heads.add(c.messages().get(0).content()));
        assertEquals(1, heads.size(), "route axis must replay one identical template head on both sides");
        String head = heads.iterator().next();
        assertTrue(head.startsWith(BehaviorKernelAssembly.KERNEL_CLAUSE),
                "route axis reuses the production head shape (kernel invariant leads)");
        assertTrue(head.contains("released template body"), "the family's full-release row is the replayed template");

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(coreGateway).postGoldenDriftVerdict(body.capture());
        Map<String, Object> sent = body.getValue();
        assertEquals("route", sent.get("comparisonKind"));
        assertEquals("glm-4.6", sent.get("controlRef"));
        assertEquals("glm-5.0", sent.get("candidateRef"));
        assertNull(sent.get("candidateVersionId"), "route axis must not claim a template FK");
        assertNull(sent.get("controlVersionId"));
        assertEquals("case-gen", sent.get("templateDomain"));
        assertEquals("golden-case-gen", sent.get("packName"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pairs = (List<Map<String, Object>>) sent.get("pairs");
        assertEquals(2, pairs.size());
        assertEquals(1, result.driftedCount());
        assertEquals(2, result.sampleTotal());
    }

    @Test
    void routeReplayRefusesIdenticalModelRowsBeforeAnyCallAndNamesBothSides() {
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.replayRouteSwitch(routeRequest("glm-4.6", " glm-4.6 ")));
        assertTrue(error.getMessage().contains("glm-4.6"), "refusal must name the colliding route");
        verify(llmFallbackChain, never())
                .chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean());
        verify(coreGateway, never()).postGoldenDriftVerdict(any());
    }

    @Test
    void routeReplayRefusesWhenTheFamilyHasNoReleasedControlRow() {
        when(coreGateway.promptTemplateLatestByStatus("case-gen-prompt", "case-gen", 2)).thenReturn(null);
        ValidationException error = assertThrows(ValidationException.class,
                () -> service.replayRouteSwitch(routeRequest("glm-4.6", "glm-5.0")));
        assertTrue(error.getMessage().contains("case-gen"), "refusal must name the template family");
        verify(llmFallbackChain, never())
                .chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean());
    }

    // ---- head axis (same template+model, old vs new assembler head) ----

    @Test
    void headReplayComparesBareAndKernelPrefixedHeadsAndLandsHeadAxis() {
        stubReleasedFamilyRow();
        when(llmFallbackChain.chat(any(ChatCompletionRequest.class), anyLong(), any(), anyBoolean()))
                .thenReturn(out("same answer"));
        when(coreGateway.postGoldenDriftVerdict(any())).thenReturn(true);

        service.replayHeadSwitch(new GoldenHeadReplayRequest("case-gen", "case-gen-prompt", 1L, "glm-4.6"));

        ArgumentCaptor<ChatCompletionRequest> calls = ArgumentCaptor.forClass(ChatCompletionRequest.class);
        verify(llmFallbackChain, times(4)).chat(calls.capture(), eq(1L), any(), eq(true));
        Set<String> heads = new HashSet<>();
        for (ChatCompletionRequest sent : calls.getAllValues()) {
            assertEquals("glm-4.6", sent.model(), "head axis pins one model on both sides");
            heads.add(sent.messages().get(0).content());
        }
        assertEquals(2, heads.size(), "old and new head shapes must actually differ");
        assertTrue(heads.contains("released template body"),
                "the legacy head sends the bare template body as-is");
        assertTrue(heads.contains(BehaviorKernelAssembly.KERNEL_CLAUSE + "\n\n" + "released template body"),
                "the current head is the production shape: kernel invariant leads the body");

        ArgumentCaptor<Map<String, Object>> body = ArgumentCaptor.forClass(Map.class);
        verify(coreGateway).postGoldenDriftVerdict(body.capture());
        Map<String, Object> sent = body.getValue();
        assertEquals("head", sent.get("comparisonKind"));
        assertEquals(GoldenReplayServiceImpl.HEAD_REF_LEGACY, sent.get("controlRef"));
        assertEquals(GoldenReplayServiceImpl.HEAD_REF_KERNEL, sent.get("candidateRef"));
        assertNull(sent.get("candidateVersionId"), "head axis must not claim a template FK");
    }
}
