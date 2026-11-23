package com.sixbean.testpike.core.common.clientobject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.clientobject.Response;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponseTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void builderSuccess_序列化结构正确() throws Exception {
        Response<Long> resp = Response.builderSuccess(42L);
        JsonNode node = mapper.readTree(mapper.writeValueAsString(resp));
        assertEquals("0", node.get("code").asText(), "成功 code 必须为 0");
        assertEquals(42, node.get("data").asLong(), "data 必须为业务数据");
        assertTrue(node.has("msg"), "必须包含 msg（文档 message 语义）");
    }

    @Test
    void builderFail_业务错误码非0且带文案() throws Exception {
        Response<Void> resp = Response.builderFail("40901", "用例版本冲突，请刷新后重试");
        JsonNode node = mapper.readTree(mapper.writeValueAsString(resp));
        assertEquals("40901", node.get("code").asText(), "错误码必须非 0");
        assertEquals("用例版本冲突，请刷新后重试", node.get("msg").asText());
        assertFalse(resp.isSuccess(), "isSuccess() 必须为 false");
    }

    @Test
    void isSuccess_仅code为0时为真() {
        assertTrue(Response.builderSuccess().isSuccess());
        assertFalse(Response.builderFail("40001", "x").isSuccess());
        assertFalse(Response.builderFail("0x", "x").isSuccess());
    }

    @Test
    void builderSuccess_无数据时data为空() throws Exception {
        JsonNode node = mapper.readTree(mapper.writeValueAsString(Response.builderSuccess()));
        assertTrue(!node.has("data") || node.get("data").isNull(),
                "无 data 时字段可省略或为 null");
    }
}
