package com.sixbean.testpike.aiservice.doc.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * AP-4(3) the extraction parser must fold back the fifth output key
 * {@code evidence} (a verbatim excerpt of the source text supporting the item) into
 * the entry map, while a missing/blank evidence stays null — the core contract
 * tolerates an absent anchor (older models simply do not emit it).
 */
class ExtractJsonParserEvidenceTest {

    private final ExtractJsonParser parser = new ExtractJsonParser(new ObjectMapper());

    @Test
    void parsesEvidenceAsFifthKey() {
        String content = "{\"requirements\":[{\"title\":\"下单库存校验\","
                + "\"acceptanceCriteria\":\"库存不足提示下单失败\",\"chapter\":\"下单流程\","
                + "\"confidence\":0.9,\"evidence\":\"用户提交订单时库存不足应提示下单失败\"}]}";
        List<Map<String, Object>> items = parser.parse(content);
        assertEquals(1, items.size());
        assertEquals("用户提交订单时库存不足应提示下单失败", items.get(0).get("evidence"),
                "evidence must be carried back as the fifth key");
    }

    @Test
    void missingEvidenceDegradesToNullWithoutFailing() {
        String content = "{\"requirements\":[{\"title\":\"支付超时取消\","
                + "\"acceptanceCriteria\":\"超时后订单取消\",\"chapter\":\"支付流程\","
                + "\"confidence\":0.8}]}";
        List<Map<String, Object>> items = parser.parse(content);
        assertNull(items.get(0).get("evidence"),
                "absent evidence is tolerated — no regeneration loop on a missing anchor");
    }

    @Test
    void blankEvidenceNormalisesToNull() {
        String content = "{\"requirements\":[{\"title\":\"重复提交防重\","
                + "\"acceptanceCriteria\":\"双击仅一单\",\"chapter\":\"下单流程\","
                + "\"confidence\":0.7,\"evidence\":\"   \"}]}";
        List<Map<String, Object>> items = parser.parse(content);
        assertNull(items.get(0).get("evidence"), "whitespace-only evidence is no anchor");
    }
}
