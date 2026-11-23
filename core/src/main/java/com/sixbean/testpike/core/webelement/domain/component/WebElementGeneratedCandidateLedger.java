package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Save-side hook (WE batch S5, piece D). The AI stage-2 door calls
 * this after a script row lands; every invented inline locator (a target
 * object instead of a {{ELEM:name}} ledger reference) must enter the page
 * element library as a CANDIDATE row, so the human approval panel sees
 * what the model made up. The prompt side already declares this semantics
 * (reference-first clause) and is untouched here.
 * <p>Single-source discipline (R40): ingestion rides
 * {@link WebElementSurveyCollector#survey} — the same idempotent upsert
 * the whole-page survey uses (anchor-identity match, candidate merge,
 * replay writes nothing, confirmed/stale rows kept, retired rows never
 * resurrected). There is no second INSERT path.
 * <p>Page attribution = the nearest preceding resolvable goto url in
 * document order; a templated url without a scheme ({{ENV:URL}}/login)
 * has no honest page, so its steps are skipped instead of guessed.
 * <p>Fail-soft contract: registration is a soft signal — any read, parse
 * or ledger failure is one WARN line and NEVER blocks or rolls back the
 * save door.
 */
@Component
public class WebElementGeneratedCandidateLedger {

    private static final Logger log =
            LoggerFactory.getLogger(WebElementGeneratedCandidateLedger.class);

    /** Evidence-note origin for save-door registrations (assertion surface). */
    public static final String ORIGIN_NOTE = "生成链自造锚自动登记";

    private final WebElementSurveyCollector collector;
    private final ObjectMapper mapper;

    public WebElementGeneratedCandidateLedger(WebElementSurveyCollector collector,
                                              ObjectMapper mapper) {
        this.collector = collector;
        this.mapper = mapper;
    }

    /**
     * Register every invented inline locator of one saved action document
     * into the page element library as a candidate (see class javadoc).
     */
    public void recordGenerated(long projectId, String actionJson) {
        if (actionJson == null || actionJson.isBlank()) {
            return;
        }
        try {
            JsonNode root = mapper.readTree(actionJson);
            LinkedHashMap<String, ArrayNode> stepsByPage = new LinkedHashMap<>();
            for (Map.Entry<String, List<JsonNode>> page
                    : WebElementInlineAnchorWalk.walk(root).byPage().entrySet()) {
                ArrayNode grouped = JsonNodeFactory.instance.arrayNode();
                page.getValue().forEach(grouped::add);
                stepsByPage.put(page.getKey(), grouped);
            }
            for (Map.Entry<String, ArrayNode> entry : stepsByPage.entrySet()) {
                ObjectNode pool = JsonNodeFactory.instance.objectNode();
                pool.set("candidates", entry.getValue());
                collector.survey(projectId, entry.getKey(), pool.toString(), null, ORIGIN_NOTE);
            }
        } catch (Exception soft) {
            log.warn("generation candidate registration skipped (save never "
                    + "blocked) projectId={}: {}", projectId, soft.toString());
        }
    }
}
