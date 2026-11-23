package com.sixbean.testpike.common.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;


class VisualAssertContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private JsonNode step(String fields) throws Exception {
        return objectMapper.readTree("{\"action\":\"visual.assert\"," + fields + "}");
    }

    @Test
    void 全可选字段缺省_合法() throws Exception {
        assertNull(VisualAssertContract.violation(step("\"name\":\"首页\"")));
    }

    @Test
    void 容差示例口径0_02_合法() throws Exception {
        assertNull(VisualAssertContract.violation(
                step("\"tolerance\":0.02,\"baselineKey\":\"b/1.png\"")));
    }

    @Test
    void 容差越上界_点名tolerance() throws Exception {
        String v = VisualAssertContract.violation(step("\"tolerance\":1.5"));
        assertTrue(v != null && v.contains("tolerance") && v.contains("0-1"), "实际：" + v);
    }

    @Test
    void 容差负数_点名tolerance() throws Exception {
        String v = VisualAssertContract.violation(step("\"tolerance\":-0.1"));
        assertTrue(v != null && v.contains("tolerance"), "实际：" + v);
    }

    @Test
    void 容差非数字_拒() throws Exception {
        String v = VisualAssertContract.violation(step("\"tolerance\":\"0.02\""));
        assertTrue(v != null && v.contains("tolerance"), "实际：" + v);
    }

    @Test
    void 基线键空白串_拒() throws Exception {
        String v = VisualAssertContract.violation(step("\"baselineKey\":\"  \""));
        assertTrue(v != null && v.contains("baselineKey"), "实际：" + v);
    }

    @Test
    void 区域四整数完整_合法() throws Exception {
        assertNull(VisualAssertContract.violation(
                step("\"region\":{\"x\":0,\"y\":10,\"w\":800,\"h\":600}")));
    }

    @Test
    void 区域缺字段_点名region() throws Exception {
        String v = VisualAssertContract.violation(step("\"region\":{\"x\":0,\"y\":10}"));
        assertTrue(v != null && v.contains("region"), "实际：" + v);
    }

    @Test
    void 区域宽高小于1_拒() throws Exception {
        String v = VisualAssertContract.violation(
                step("\"region\":{\"x\":0,\"y\":0,\"w\":0,\"h\":600}"));
        assertTrue(v != null && v.contains("region"), "实际：" + v);
    }

    @Test
    void 区域坐标负数_拒() throws Exception {
        String v = VisualAssertContract.violation(
                step("\"region\":{\"x\":-1,\"y\":0,\"w\":10,\"h\":10}"));
        assertTrue(v != null && v.contains("region"), "实际：" + v);
    }

    @Test
    void 区域非整数值_拒() throws Exception {
        String v = VisualAssertContract.violation(
                step("\"region\":{\"x\":1.5,\"y\":0,\"w\":10,\"h\":10}"));
        assertTrue(v != null && v.contains("region"), "实际：" + v);
    }
}
