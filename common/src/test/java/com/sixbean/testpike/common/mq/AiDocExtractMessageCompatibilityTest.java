package com.sixbean.testpike.common.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * wire-compatibility contract. The extraction message gained a
 * nullable {@code dimensionHint} component (the preview's uncovered-dimension
 * chip asks for one targeted supplemental extraction round). Two-phase
 * migration rule: messages produced before the change (payload without the
 * field) must still deserialize — hint null, every legacy field intact — and
 * the legacy five-argument builder keeps compiling for existing producers and
 * test fixtures. A null hint means plain full-document extraction; the
 * downstream prompt stays byte-identical (asserted in the ai-service suite).
 */
class AiDocExtractMessageCompatibilityTest {

    private static final String LEGACY_JSON = "{\"documentId\":7,\"projectId\":3,"
            + "\"operatorId\":11,\"modelHint\":null,\"extractToken\":\"tok-1\"}";

    private static final String HINTED_JSON = "{\"documentId\":7,\"projectId\":3,"
            + "\"operatorId\":11,\"modelHint\":null,\"extractToken\":\"tok-1\","
            + "\"dimensionHint\":\"边界\"}";

    @Test
    void legacyPayloadWithoutDimensionHintStillDeserializes() throws Exception {
        AiDocExtractMessage message =
                new ObjectMapper().readValue(LEGACY_JSON, AiDocExtractMessage.class);
        assertEquals(7L, message.documentId());
        assertEquals(3L, message.projectId());
        assertEquals(11L, message.operatorId());
        assertEquals("tok-1", message.extractToken());
        assertNull(message.dimensionHint(), "旧轮次报文无该字段必须解为 null（两遍迁移零漂移）");
    }

    @Test
    void hintedPayloadCarriesTheDimensionName() throws Exception {
        AiDocExtractMessage message =
                new ObjectMapper().readValue(HINTED_JSON, AiDocExtractMessage.class);
        assertEquals("边界", message.dimensionHint());
    }

    @Test
    void legacyFiveArgumentBuilderDefaultsTheHintToNull() {
        AiDocExtractMessage message =
                new AiDocExtractMessage(7L, 3L, 11L, null, "tok-1");
        assertNull(message.dimensionHint());
        assertEquals("tok-1", message.extractToken());
    }
}
