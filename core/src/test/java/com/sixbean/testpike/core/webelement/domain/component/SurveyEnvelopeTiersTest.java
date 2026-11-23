package com.sixbean.testpike.core.webelement.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WE-S4 segment P2c proof: a survey session's element-candidates envelope
 * gains the declared viewport tier list (one additive field), while every
 * other shape — plain goal, unset key, non-candidates action, malformed
 * JSON — passes through byte-identical (zero-drift contract on the shared
 * explore-act channel). Real dev database, no mocks.
 */
@SpringBootTest(classes = CoreApplication.class)
class SurveyEnvelopeTiersTest {

    @Autowired
    private SurveyEnvelopeTiers surveyEnvelopeTiers;

    @Autowired
    private ConfigCacheHolder configCacheHolder;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SURVEY_GOAL =
            WebElementSurveyCollector.SURVEY_GOAL_PREFIX + "http://localhost:5173/demo";

    private static final String CANDIDATES_ENVELOPE =
            "{\"kind\":\"explore-act\",\"action\":\"element-candidates\","
                    + "\"url\":\"http://localhost:5173/demo\",\"arguments\":{}}";

    @Test
    void surveyCandidatesEnvelopeGainsDeclaredTiers() throws Exception {
        configCacheHolder.put(SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY, "mobile, tablet");

        String out = surveyEnvelopeTiers.attach(CANDIDATES_ENVELOPE, SURVEY_GOAL);

        JsonNode envelope = MAPPER.readTree(out);
        JsonNode tiers = envelope.path(SurveyEnvelopeTiers.ENVELOPE_TIERS_FIELD);
        assertTrue(tiers.isArray(), "survey envelope must carry the tier array, actual=" + out);
        assertEquals(2, tiers.size(), "declared tiers must survive blank-trimming");
        assertEquals("mobile", tiers.get(0).asText());
        assertEquals("tablet", tiers.get(1).asText());
        assertEquals("explore-act", envelope.path("kind").asText());
        assertEquals("element-candidates", envelope.path("action").asText());
        assertEquals("http://localhost:5173/demo", envelope.path("url").asText());
    }

    @Test
    void plainGoalEnvelopeStaysByteIdentical() {
        String out = surveyEnvelopeTiers.attach(CANDIDATES_ENVELOPE, "摸清登录流程");
        assertEquals(CANDIDATES_ENVELOPE, out, "non-survey goals must not gain any field");
    }

    @Test
    void unsetKeyKeepsSurveyEnvelopeUnchanged() {
        configCacheHolder.put(SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY, "");

        String out = surveyEnvelopeTiers.attach(CANDIDATES_ENVELOPE, SURVEY_GOAL);

        assertEquals(CANDIDATES_ENVELOPE, out,
                "unset tier declaration honestly means default viewport only");
        assertFalse(out.contains(SurveyEnvelopeTiers.ENVELOPE_TIERS_FIELD));
    }

    @Test
    void otherSurveyActionsStayUnchanged() {
        configCacheHolder.put(SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY, "mobile,tablet");
        String clickEnvelope = "{\"kind\":\"explore-act\",\"action\":\"click-path\","
                + "\"url\":\"http://localhost:5173/demo\",\"arguments\":{}}";

        String out = surveyEnvelopeTiers.attach(clickEnvelope, SURVEY_GOAL);

        assertEquals(clickEnvelope, out,
                "only the collection step replays tiers; click/input/assert envelopes untouched");
    }

    @Test
    void malformedEnvelopePassesThroughFailSoft() {
        configCacheHolder.put(SurveyEnvelopeTiers.VIEWPORT_CLASSES_KEY, "mobile,tablet");

        String out = surveyEnvelopeTiers.attach("not-a-json", SURVEY_GOAL);

        assertEquals("not-a-json", out,
                "envelope shape legality is guarded at the write side; attach never mints errors");
    }
}
