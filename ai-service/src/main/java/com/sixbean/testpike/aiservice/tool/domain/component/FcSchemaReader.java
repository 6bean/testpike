package com.sixbean.testpike.aiservice.tool.domain.component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.aiservice.gen.infrastructure.remote.ToolFcAssemblyRemoteItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Canonical input_schema → OpenAI {@code parameters} object reader (single
 * source shared by the FC assembly list and the deferred-catalog expansion —
 * one schema-shape verdict, no second copy). Unparseable or non-object JSON
 * fails closed to {@code null}: the caller drops that tool rather than
 * shipping a sick definition (provider 400 would break the whole request).
 */
final class FcSchemaReader {

    private static final Logger log = LoggerFactory.getLogger(FcSchemaReader.class);

    private FcSchemaReader() {
    }

    /** Parse the item's schema; {@code null} = fail-closed drop (warn observable). */
    static JsonNode read(ObjectMapper mapper, ToolFcAssemblyRemoteItem item) {
        try {
            JsonNode node = mapper.readTree(item.inputSchemaJson());
            if (!node.isObject()) {
                log.warn("FC 装配剔除工具 {}（toolId={}）：input_schema 非 JSON 对象",
                        item.name(), item.toolId());
                return null;
            }
            return node;
        } catch (Exception e) {
            log.warn("FC 装配剔除工具 {}（toolId={}）：input_schema 解析失败 {}",
                    item.name(), item.toolId(), e.getMessage());
            return null;
        }
    }
}
