package com.sixbean.testpike.common.util;

import java.util.List;


public final class LlmExtractMarkers {

 
    public static final String STAGE1_CASE = "STAGE1_CASE_JSON";

 
    public static final String STAGE2_ACTION = "STAGE2_ACTION_JSON";

 
    public static final String DOC_EXTRACT_REQUIREMENTS = "DOC_EXTRACT_REQUIREMENTS_JSON";

 
    public static final String DOC_EXTRACT_SUMMARY = "DOC_EXTRACT_SUMMARY_JSON";

    
    public static final String FC_TOOL_CALL = "FC_TOOL_CALL";

    
    public static final String SPEC_DRAFT_EXTRACT = "SPEC_DRAFT_EXTRACT_JSON";

    
    public static final String JUDGE_QA_GEN = "JUDGE_QA_GEN_JSON";

    /**
 * AI mock drafting contract marker system carries the
 * marker → the local stub provider answers with a deterministic draft-bundle
 * JSON; real providers ignore the marker while human-tick confirmation still
 * gates every rule written to the rule source).
 */
    public static final String MOCK_DRAFT_GEN = "MOCK_DRAFT_GEN_JSON";

    /**
 * A-3 LLM fallback answer contract marker the sync
 * generation door in ai-service carries it in the system prompt so the
 * local stub provider answers with a deterministic {status,headers,body}
 * object; real providers ignore the marker while the core orchestrator
 * still gates every store through the dual-switch matrix).
 */
    public static final String MOCK_LLM_FALLBACK = "MOCK_LLM_FALLBACK_JSON";

    
    public static final List<String> ECHO_MARKERS = List.of(
            STAGE1_CASE, STAGE2_ACTION, DOC_EXTRACT_REQUIREMENTS, DOC_EXTRACT_SUMMARY,
            SPEC_DRAFT_EXTRACT, JUDGE_QA_GEN, MOCK_DRAFT_GEN, MOCK_LLM_FALLBACK);

    private LlmExtractMarkers() {
    }
}

