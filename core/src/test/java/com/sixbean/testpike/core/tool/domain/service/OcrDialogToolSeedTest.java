package com.sixbean.testpike.core.tool.domain.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.domain.component.OcrDialogToolSeedInitializer;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Dialog OCR built-in tool seed test .
 * The conversation routes these two rows by their name literals — the anchor
 * seam (DialogOcrReadAnchor) stamps server-side identity only for exactly
 * these names — so this test pins the factory shape against drift: one row per
 * post, HTTP exec form dialled at the ai-service doors, read schema exposing
 * only the attached-image handle, and a clean re-run.
 * <p>No TRUNCATE here (baseline-residency rule R37): the seeded rows ARE
 * factory data on the shared dev database, so the test asserts the runner's
 * own idempotent outcome instead of clearing the table.
 */
@SpringBootTest(classes = CoreApplication.class)
class OcrDialogToolSeedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String> INTENTS = List.of("ocr.text_extract", "ocr.grid_read");

    @Autowired
    private OcrDialogToolSeedInitializer seedInitializer;

    @Autowired
    private ToolService toolService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void ensureFactorySeedPair() {
        // @SpringBootTest never runs ApplicationRunner beans, so the startup hook that
        // guarantees the pair on a real boot does not fire here; on a freshly provisioned
        // chain slot the tp_tool rows would be absent. Invoke the same official idempotent
        // channel the hook uses (R37: single seed channel), then assertions below pin the
        // factory shape and re-running stays a no-op.
        seedInitializer.seedMissing();
    }

    @Test
    void bothDialogOcrToolsExistInPublishedForm() {
        for (String intent : INTENTS) {
            Long id = jdbcTemplate.queryForObject(
                    "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, intent);
            assertNotNull(id, intent + " must be registered as a tool");
            ToolInfo tool = toolService.get(id);
            assertEquals(intent, tool.name(),
                    "name literal = the anchor seam routing token, must not drift");
            assertEquals(1, tool.execKind(), intent + " uses the HTTP exec form");
            assertEquals(3, tool.status(), intent + " must finish the approval chain (published)");
            Integer systemRows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' = ?"
                            + " AND project_id IS NULL",
                    Integer.class, intent);
            assertEquals(1, systemRows, intent + " lives in the system-level bucket");
        }
    }

    @Test
    void dialUrlTargetsTheAiServiceDialogDoors() {
        String textUrl = specField("ocr.text_extract", "url");
        String gridUrl = specField("ocr.grid_read", "url");
        assertTrue(textUrl.endsWith("/internal/ocr/text-extract"),
                "character post must dial the ai-service text door, got " + textUrl);
        assertTrue(gridUrl.endsWith("/internal/ocr/grid-read"),
                "grid post must dial the ai-service grid door, got " + gridUrl);
        // No authRef token: the internal doors are unauthenticated pass-through
        // (core's executeHttp treats a missing authRef as none, not as a failure).
        assertTrue(specField("ocr.text_extract", "authRef") == null
                        && specField("ocr.grid_read", "authRef") == null,
                "internal dialog doors must not carry a phantom credential reference");
    }

    @Test
    void schemasExposeOnlyTheAttachedImageHandleAndStayReadShaped() {
        for (String intent : INTENTS) {
            JsonNode schema = read(schemaJson(intent));
            assertTrue(schema.path("x-write").isMissingNode(),
                    intent + " is a read shape: the dispatcher runs it without a confirm ticket");
            JsonNode props = schema.path("properties");
            List<String> exposed = new ArrayList<>();
            props.fieldNames().forEachRemaining(exposed::add);
            assertEquals(List.of("objectKey"), exposed,
                    intent + " must expose exactly the attached-image handle — anchors are"
                            + " server-stamped and a model-written identity is always overwritten");
            assertTrue(textList(schema.path("required")).contains("objectKey"),
                    intent + " must declare objectKey so the composer has a place for the handle");
        }
    }

    @Test
    void catalogDescriptionsSeparateTheTwoPosts() {
        // The model picks a post by the directory description (L3 dual-instruction
        // selection): character transcription vs table reading must be worded apart,
        // and each row must point at its sibling and forbid calling both for the
        // same image — a redundant sibling call in one round costs a second engine
        // round trip without changing the answer.
        String textDesc = description("ocr.text_extract");
        String gridDesc = description("ocr.grid_read");
        assertTrue(textDesc.contains("文字") && !textDesc.contains("行列网格"),
                "character post description must not claim the grid job");
        assertTrue(gridDesc.contains("表格"), "grid post description must name tables");
        assertTrue(textDesc.contains("ocr.grid_read") && textDesc.contains("二选一"),
                "character post description must name the sibling post and forbid "
                        + "calling both for one image, got " + textDesc);
        assertTrue(gridDesc.contains("ocr.text_extract") && gridDesc.contains("二选一"),
                "grid post description must name the sibling post and forbid "
                        + "calling both for one image, got " + gridDesc);
    }

    @Test
    void reRunningTheSeedCreatesNothing() {
        // Idempotency anchor = exec_spec intent: a complete pair re-runs clean.
        assertEquals(0, seedInitializer.seedMissing());
        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE exec_spec ->> 'intent' IN "
                        + "('ocr.text_extract','ocr.grid_read')", Integer.class);
        assertEquals(2, rows, "re-seed must never duplicate the pair");
    }

    private ToolInfo toolFor(String intent) {
        Long id = jdbcTemplate.queryForObject(
                "SELECT id FROM tp_tool WHERE exec_spec ->> 'intent' = ?", Long.class, intent);
        assertNotNull(id, intent + " row missing");
        return toolService.get(id);
    }

    private String specField(String intent, String field) {
        JsonNode node = read(toolFor(intent).execSpecJson()).path(field);
        return node.isMissingNode() || node.isNull() ? null : node.asText();
    }

    private String schemaJson(String intent) {
        return toolFor(intent).inputSchemaJson();
    }

    private String description(String intent) {
        return toolFor(intent).description();
    }

    private static JsonNode read(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static List<String> textList(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(node -> out.add(node.asText()));
        return out;
    }
}
