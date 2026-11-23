package com.sixbean.testpike.admin.apitest.api.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;


class RequestViewContractStaleTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void 契约变更徽标_从core线格式读回() throws Exception {
        String coreWire = """
                {"id":9,"collectionId":3,"name":"下单","method":"POST","urlPath":"/orders",
                 "headersJson":"[]","bodyText":"","authJson":"{}","assertionsJson":"[]",
                 "promotedCaseId":null,"contractStale":true,"mockStale":true}
                """;
        RequestView view = mapper.readValue(coreWire, RequestView.class);
        assertTrue(view.contractStale(), "core 挂标=true 时 admin 视图必须读回 true（字段名对齐）");
        assertTrue(view.mockStale(), "mock twin badge must survive the wire (field-name drift = silent loss)");
    }

    @Test
    void 未挂标载荷_读回false() throws Exception {
        String coreWire = """
                {"id":9,"collectionId":3,"name":"下单","method":"POST","urlPath":"/orders",
                 "headersJson":"[]","bodyText":"","authJson":"{}","assertionsJson":"[]",
                 "promotedCaseId":null,"contractStale":false}
                """;
        RequestView view = mapper.readValue(coreWire, RequestView.class);
        assertFalse(view.contractStale());
        assertFalse(view.mockStale(), "absent twin field reads back false (old-core rows never crash the bridge)");
    }
}
