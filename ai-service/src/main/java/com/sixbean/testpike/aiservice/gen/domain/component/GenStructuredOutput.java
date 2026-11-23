package com.sixbean.testpike.aiservice.gen.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.CoreGateway;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatCompletionRequest;
import com.sixbean.testpike.aiservice.llm.api.dto.ChatResponseFormat;
import com.sixbean.testpike.aiservice.llm.domain.component.LlmEndpointConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>Stage 1 (case draft) and stage 2 (action JSON v2) ride provider-side
 * schema-constrained decoding when BOTH gates pass: the hot-update K8 switch
 * {@code ai.gen.structured-output.enabled} (factory ON, absent row = on) and
 * the per-model capability row flag ({@code structured_output}; absent column
 * in an older core payload = capable). The goal is format-level re-rolls
 * trending to zero — the schemas below describe the OUTPUT SHAPE only; all
 * semantic rules stay owned by the existing validators (CaseJsonParser,
 * SelfCheckValidator, ActionJsonValidator ring checks), never a second rule
 * source.
 * <p>Honest fallback: an endpoint that still refuses the block (400 on the
 * wire) is retried once with the block stripped and the refusal is logged —
 * the legacy parse-and-revise path continues from that point with zero drift.
 */
public final class GenStructuredOutput {

    /** K8 hot-update switch; read per call, absent row = factory ON (R32). */
    public static final String GATE_KEY = "ai.gen.structured-output.enabled";

    /** Stage 1 output contract name carried on the wire. */
    public static final String CASE_CONTRACT = "testpike_case_draft_v1";

    /** Stage 2 output contract name carried on the wire. */
    public static final String SCRIPT_CONTRACT = "testpike_action_script_v2";

    private static final Logger log = LoggerFactory.getLogger(GenStructuredOutput.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Shape of the stage 1 draft (fields mirrored from CaseJsonParser expectations). */
    private static final String CASE_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "title": {"type": "string"},
                "precondition": {"type": "string"},
                "expectedResult": {"type": "string"},
                "steps": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "description": {"type": "string"},
                      "isAssert": {"type": "integer", "enum": [0, 1]}
                    },
                    "required": ["description"]
                  }
                },
                "priority": {"type": "integer"},
                "confidence": {"type": "integer"},
                "endType": {"type": "string", "enum": ["web", "mobile", "pos", "multi"]}
              },
              "required": ["title", "expectedResult", "steps"]
            }
            """;

    /**
 * Shape of the stage 2 action JSON v2 envelope (04 contract, shape only).
 * The required set and the step-level {@code url} property mirror the
 * surface ActionJsonValidator enforces : under a
 * schema-enforcing endpoint the model can only emit what the schema asks
 * for, so a field the validator always demands must be required HERE —
 * a validator named in the ring feedback alone cannot outvote the schema.
 * env's inner shape stays permissive: the validator only requires its
 * presence, no second rule is welded into the schema (04 rule 2 keys ride
 * the prompt-side sample).
 */
    private static final String SCRIPT_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "version": {"type": "string", "enum": ["v2"]},
                "caseId": {"type": "string"},
                "title": {"type": "string"},
                "endType": {"type": "string", "enum": ["web", "mobile", "pos", "multi"]},
                "setup": {"type": "array", "items": SCRIPT_STEP},
                "steps": {"type": "array", "minItems": 1, "items": SCRIPT_STEP},
                "env": {"type": "object"},
                "metadata": {"type": "object"}
              },
              "required": ["version", "caseId", "env", "steps"]
            }
            """
            .replace("SCRIPT_STEP", """
                    {
                      "type": "object",
                      "properties": {
                        "id": {"type": "string"},
                        "action": {"type": "string"},
                        "url": {"type": "string"},
                        "target": {},
                        "description": {"type": "string"}
                      },
                      "required": ["action"]
                    }
                    """);

    private GenStructuredOutput() {
    }

    /** Hot gate: K8 row absent or unparsable = factory ON (AI features ship enabled). */
    public static boolean gateOn(CoreGateway coreGateway) {
        String raw = coreGateway.getSysConfig(GATE_KEY);
        if (raw == null) {
            return true;
        }
        try {
            return Integer.parseInt(raw.trim()) != 0;
        } catch (NumberFormatException malformed) {
            log.warn("K8 开关 {} 取值无法解析 raw={}，按出厂开处理", GATE_KEY, raw);
            return true;
        }
    }

    /**
     * Attach the schema-constrained response format when both gates pass;
     * otherwise hand back the request untouched (legacy shape byte-identical).
     */
    public static ChatCompletionRequest decorate(ChatCompletionRequest request,
                                                 LlmEndpointConfig primary, boolean gateOn,
                                                 String contract) {
        if (!gateOn || !primary.structuredOutputEnabled()) {
            return request;
        }
        return request.withResponseFormat(
                ChatResponseFormat.jsonSchema(contract, true, schemaFor(contract)));
    }

    /** Strip helper used by the refusal degrade path (null-safe). */
    public static ChatCompletionRequest strip(ChatCompletionRequest request) {
        return request.responseFormat() == null ? request : request.withoutResponseFormat();
    }

    /**
     * Format-class filter over ring disputes: the shape-level claims a
     * schema-constrained endpoint should have made impossible (parse failure,
     * completeness self-check, action-JSON contract violations named by the
     * shared validator on the script channel). Semantic disputes (coverage,
     * evidence anchors, posture) are deliberately excluded — they are not
     * enforcement evidence. The script-channel prefixes below are produced
     * by AiGenServiceImpl's verdict/save-door wording; the word family is
     * the SINGLE recognition source (R40) — adding a new format-class
     * dispute wording must extend this family, not a per-caller copy.
     */
    public static boolean hasFormatResidual(java.util.List<String> disputes) {
        if (disputes == null) {
            return false;
        }
        return disputes.stream().anyMatch(d -> d != null
                && (d.contains("上次输出格式不合格") || d.contains("上次输出未通过完整性自检")
                        || d.contains("上次脚本不合格") || d.contains("落库被脚本静态校验拒")));
    }

    /**
     * Form rule for a schema-bearing stage1 pass (single rule source): the
     * wire must drop the function-calling tool list whenever the schema block
     * rides and either (a) this pass is a revision fed by a format-class
     * dispute (the mounted-tools form already produced one shape violation —
     * OpenAI-compat gateways accept response_format but silently drop the
     * schema when tools ride the same request, so re-rolling in the same
     * shape can never converge), or (b) the capability ledger already knows
     * this model's tools+schema form is unenforced (steer the FIRST pass of
     * the model into the enforceable form as well). Without the schema on the
     * wire there is nothing to enforce and the legacy FC shape stays.
     */
    public static boolean preferSchemaOnlyForm(boolean schemaRides, boolean formatReRoll,
                                               boolean ledgerUnenforced) {
        return schemaRides && (formatReRoll || ledgerUnenforced);
    }

    /** Schema tree for one contract name (parsed from the constants above). */
    public static JsonNode schemaFor(String contract) {
        try {
            return MAPPER.readTree(CASE_CONTRACT.equals(contract) ? CASE_SCHEMA : SCRIPT_SCHEMA);
        } catch (Exception broken) {
            // A broken constant can never take the generation chain down: the
            // caller's legacy path is the honest fallback by construction.
            log.warn("结构化输出契约 {} 的 schema 常量解析失败，本次调用回退旧解析路径",
                    contract, broken);
            return null;
        }
    }
}
