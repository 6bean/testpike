package com.sixbean.testpike.core.apitest.domain.component;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Pins the R63 three-element contract for every refusal raised by the
 * promotion doors (single request / whole flow) without booting
 * infrastructure: the offending value is echoed, the repair entry is
 * quoted with its on-screen label, an action word tells the user what to
 * do next, and no sentence ends in a dead end.
 */
class PromotionRefusalsL2Test {

    private static void assertThreeElements(String sentence, String... echoedValues) {
        assertFalse(sentence.isBlank(), "refusal sentence must not be blank");
        for (String value : echoedValues) {
            assertTrue(sentence.contains(value),
                    "offending value not echoed: " + value + " in [" + sentence + "]");
        }
        assertTrue(sentence.contains("「"),
                "repair entry label must be quoted: [" + sentence + "]");
        assertTrue(sentence.contains("请") || sentence.contains("可点") || sentence.contains("建议"),
                "action word missing: [" + sentence + "]");
    }

    @Test
    void alreadyPromotedEchoesCaseNoAndPointsToCaseList() {
        assertThreeElements(PromotionRefusals.alreadyPromoted("TC-000123"), "TC-000123");
    }

    @Test
    void concurrentPromotionEchoesCaseNo() {
        assertThreeElements(PromotionRefusals.concurrentPromoted("TC-000456"), "TC-000456");
    }

    @Test
    void emptyCollectionNamesCollectionAndCreateRequestEntry() {
        assertThreeElements(PromotionRefusals.emptyCollection("下单链路"), "下单链路");
    }

    @Test
    void memberAlreadyPromotedNamesMemberAndCase() {
        assertThreeElements(PromotionRefusals.memberAlreadyPromoted("创建订单", "TC-000789"),
                "创建订单", "TC-000789");
    }

    @Test
    void memberUnsupportedProtocolNamesMemberAndProtocol() {
        assertThreeElements(PromotionRefusals.memberUnsupportedProtocol("行情推送", "ws"),
                "行情推送", "ws");
    }

    @Test
    void memberOrchestrationNamesMemberAndOrchestrationFields() {
        assertThreeElements(PromotionRefusals.memberOrchestration("轮询状态"), "轮询状态");
    }

    @Test
    void memberAssertionViolationCarriesMemberNameAndMapperReason() {
        assertThreeElements(PromotionRefusals.memberAssertionViolation("查询库存", "第 2 行缺少校验字段"),
                "查询库存", "第 2 行缺少校验字段");
    }

    @Test
    void memberConcurrentPromotionNamesMemberAndCase() {
        assertThreeElements(PromotionRefusals.memberConcurrentPromoted("取消订单", "TC-000321"),
                "取消订单", "TC-000321");
    }

    @Test
    void bearerTokenMissingNamesRequestAndTokenField() {
        assertThreeElements(PromotionRefusals.bearerTokenMissing("支付回调"), "支付回调");
    }

    @Test
    void headerNameMissingNamesRequestAndHeaderNameField() {
        assertThreeElements(PromotionRefusals.headerNameMissing("网关探活"), "网关探活");
    }

    @Test
    void authTypeUnsupportedEchoesTypeAndRequestName() {
        assertThreeElements(PromotionRefusals.authTypeUnsupported("oauth2", "用户中心登录"),
                "oauth2", "用户中心登录");
    }

    @Test
    void envelopeAssemblyFailureNamesSubject() {
        assertThreeElements(PromotionRefusals.envelopeAssemblyFailed("下单流程"), "下单流程");
    }

    @Test
    void invalidJsonNamesRequestAndFieldLabel() {
        assertThreeElements(PromotionRefusals.invalidJson("断言组", "退款申请"), "退款申请", "断言组");
    }
}
