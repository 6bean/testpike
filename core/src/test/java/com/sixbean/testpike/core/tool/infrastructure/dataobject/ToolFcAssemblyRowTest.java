package com.sixbean.testpike.core.tool.infrastructure.dataobject;

import com.sixbean.testpike.core.tool.api.dto.ToolFcAssemblyItem;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Assembly-row projection tests: the registry function name carried in
 * {@code exec_spec->>'function'} must reach the FC assembly item verbatim
 * (coverageSummary / assertionDensity camelCase literals — the same face
 * values the internal analysis door accepts), while the user-facing display
 * name never doubles as a routing key. The SQL alias side is exercised by the
 * later three-way reconciliation segment; this class pins the row-to-record
 * mapping and the fail-closed write-flag fallback.
 */
class ToolFcAssemblyRowTest {

    @Test
    void functionNamePassesThroughToAssemblyItem() {
        ToolFcAssemblyRow row = new ToolFcAssemblyRow();
        row.setToolId(91L);
        row.setName("分析·需求覆盖摘要");
        row.setType(2);
        row.setExecKind(5);
        row.setInputSchemaJson("{\"type\":\"object\"}");
        row.setTimeoutMs(30000);
        row.setFunctionName("coverageSummary");

        ToolFcAssemblyItem item = row.toItem();

        assertEquals(5, item.execKind());
        assertEquals("coverageSummary", item.functionName());
        assertFalse(item.write(), "analysis schema carries no x-write = read semantics");
    }

    @Test
    void absentFunctionNameStaysNullNotGuessed() {
        ToolFcAssemblyRow row = new ToolFcAssemblyRow();
        row.setToolId(11L);
        row.setName("kb_http");
        row.setType(2);
        row.setExecKind(1);
        row.setInputSchemaJson("{\"type\":\"object\"}");
        row.setTimeoutMs(5000);

        assertNull(row.toItem().functionName(),
                "an HTTP row without exec_spec.function stays null — never guessed from the name");
    }
}
