package com.sixbean.testpike.aiservice.llm.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatMessage;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatResponseFormat;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolDef;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatToolFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Live prober that settles whether an endpoint enforces schema-constrained
 * decoding in a given wire form, and writes the proven truth back into
 * {@link LlmOutputCapabilityLedger} (ticket-② promotion channel).
 * <p>The ledger otherwise only learns the negative class from generation
 * traffic (a format-class dispute while the block was mounted). A row stuck
 * at enforced = 0 meanwhile steers every schema-bearing call of that model
 * into the tool-free form forever, even after the gateway learned to obey —
 * this component closes the loop by re-asking the endpoint directly.
 * <p>Probe shape: one tiny tool rides the request next to the json_schema
 * block, exactly the tools+schema co-existence the unenforced class was
 * observed in. Verdict rule: every required key of the probe schema present
 * in the returned content = enforced. Mock providers and transport failures
 * answer {@link LlmProbeVerdict#UNKNOWN} and touch nothing (absence of
 * evidence is never evidence of a change).
 */
@org.springframework.stereotype.Component
public class LlmCapabilityProber {

    private static final Logger log = LoggerFactory.getLogger(LlmCapabilityProber.class);

    /** Marker property the probe schema demands; presence = enforcement proof. */
    static final String PROBE_REQUIRED_KEY = "probe_status";

    private static final String PROBE_TOOL_NAME = "capability_probe_ping";

    private final LlmProviderFactory providerFactory;
    private final LlmOutputCapabilityLedger ledger;
    private final ObjectMapper objectMapper;

    public LlmCapabilityProber(LlmProviderFactory providerFactory,
                               LlmOutputCapabilityLedger ledger,
                               ObjectMapper objectMapper) {
        this.providerFactory = providerFactory;
        this.ledger = ledger;
        this.objectMapper = objectMapper;
    }

    /**
     * Probe one (endpoint, form) pair and record what was proved.
     *
     * @param endpoint live tp_llm_config row resolved by the caller
     *                 (the scheduler passes in-service rows; never a stub)
     * @param form     one of {@link LlmOutputCapabilityLedger#KNOWN_FORMS}
     * @return the three-state verdict of this probe round
     */
    public LlmProbeVerdict probeAndRecord(LlmEndpointConfig endpoint, String form) {
        if (endpoint == null || endpoint.modelName() == null || form == null) {
            return LlmProbeVerdict.UNKNOWN;
        }
        if (providerFactory.isMock()) {
            log.info("Capability probe skipped: current provider is a stand-in (mock) endpoint, enforcement behaviour cannot be proven against the real line model={}",
                    endpoint.modelName());
            return LlmProbeVerdict.UNKNOWN;
        }
        LlmChatOutcome outcome = askOnce(endpoint, form);
        LlmProbeVerdict verdict = classify(outcome);
        switch (verdict) {
            case PROMOTED -> {
                ledger.promoteEnforced(endpoint.modelName(), form);
                log.info("Capability probe promoted: endpoint enforces structured output in this form model={} form={}",
                        endpoint.modelName(), form);
            }
            case STILL_UNENFORCED -> {
                ledger.markProbed(endpoint.modelName(), form);
                log.info("Capability reprobe still not enforcing: schema calls of this form stay rerouted to the enforceable form model={} form={}",
                        endpoint.modelName(), form);
            }
            case UNKNOWN -> log.warn("Capability probe inconclusive (provider failure or no judgeable response body), nothing booked this round model={} form={}",
                    endpoint.modelName(), form);
            default -> {
            }
        }
        return verdict;
    }

    /** Raw exchange of one probe call: transport truth kept separate from the verdict. */
    record LlmChatOutcome(boolean transportOk, String content, boolean toolCallWithoutContent) {
    }

    private LlmChatOutcome askOnce(LlmEndpointConfig endpoint, String form) {
        try {
            ChatCompletionRequest request = buildProbeRequest(endpoint.modelName(), form);
            var result = providerFactory.chatFor(request, endpoint);
            String content = result == null ? null : result.content();
            boolean toolOnly = result != null && content == null
                    && result.toolCalls() != null && !result.toolCalls().isEmpty();
            return new LlmChatOutcome(result != null, content, toolOnly);
        } catch (RuntimeException probeFailed) {
            log.warn("Capability probe call failed model={} form={} reason={}",
                    endpoint.modelName(), form, probeFailed.getMessage());
            return new LlmChatOutcome(false, null, false);
        }
    }

    /** Three-state judgement; only a readable content sample can prove either way. */
    LlmProbeVerdict classify(LlmChatOutcome outcome) {
        if (!outcome.transportOk() || outcome.toolCallWithoutContent()) {
            return LlmProbeVerdict.UNKNOWN;
        }
        String content = outcome.content();
        if (content == null || content.isBlank()) {
            return LlmProbeVerdict.UNKNOWN;
        }
        try {
            JsonNode parsed = objectMapper.readTree(content);
            JsonNode required = probeSchema().get("required");
            if (parsed == null || !parsed.isObject() || required == null) {
                return LlmProbeVerdict.STILL_UNENFORCED;
            }
            for (JsonNode key : required) {
                if (!parsed.has(key.asText())) {
                    return LlmProbeVerdict.STILL_UNENFORCED;
                }
            }
            return LlmProbeVerdict.PROMOTED;
        } catch (com.fasterxml.jackson.core.JacksonException notJson) {
            // Accepted the block, ignored it: free prose is the class this row exists for.
            return LlmProbeVerdict.STILL_UNENFORCED;
        }
    }

    private ChatCompletionRequest buildProbeRequest(String model, String form) {
        JsonNode schema = probeSchema();
        ChatResponseFormat format = ChatResponseFormat.jsonSchema("capability_probe", true, schema);
        List<ChatToolDef> tools = LlmOutputCapabilityLedger.FORM_TOOLS_SCHEMA.equals(form)
                ? List.of(new ChatToolDef(new ChatToolFunction(PROBE_TOOL_NAME,
                        "Probe-only no-op tool used to reproduce the function-calling shape.",
                        toolParameters())))
                : null;
        return new ChatCompletionRequest(model,
                List.of(new ChatMessage("user",
                        "Respond with the required JSON object only.")),
                0.0, 128, tools, null, null, null, null, format);
    }

    private JsonNode probeSchema() {
        try {
            return objectMapper.readTree("""
                    {"type":"object",
                     "properties":{"probe_status":{"type":"string","enum":["ok"]}},
                     "required":["probe_status"],
                     "additionalProperties":false}""");
        } catch (com.fasterxml.jackson.core.JacksonException impossible) {
            throw new IllegalStateException("probe schema must parse", impossible);
        }
    }

    private JsonNode toolParameters() {
        try {
            return objectMapper.readTree(
                    "{\"type\":\"object\",\"properties\":{\"note\":{\"type\":\"string\"}}}");
        } catch (com.fasterxml.jackson.core.JacksonException impossible) {
            throw new IllegalStateException("probe tool schema must parse", impossible);
        }
    }
}
