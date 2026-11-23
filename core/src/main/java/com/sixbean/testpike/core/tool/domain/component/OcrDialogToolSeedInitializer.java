package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Registers the two read-only dialog OCR tools : the
 * character post ({@code ocr.text_extract}, engine lines with box
 * coordinates and confidence) and the grid post ({@code ocr.grid_read},
 * shared-grid assembly over the same engine lines for table-shaped images).
 * <p>These names are the routing contract itself: the ai-service anchor seam
 * (DialogOcrReadAnchor) stamps the server-side conversation anchors only for
 * exactly these two names, and the model picks a post from the directory
 * description, so both the name literals and the semantic split in the
 * wording are pinned by {@code OcrDialogToolSeedTest}.
 * <p>Form decision (no third form, R40): the tool rows follow the
 * {@code *ToolSeedInitializer} precedent — every built-in tool row in this
 * repository is created through the {@link ToolService} approval chain
 * (create → submit → approve, seed operator and approver kept apart as 1/2),
 * never through a raw table insert, so auditing and lifecycle stay the one
 * place the platform already has. The pass-through governance table is
 * deliberately NOT touched: its catalog is generated from core's own request
 * mappings and the read door refuses POST-class entries, so rows naming
 * ai-service doors would be dead data there; these endpoints are reachable
 * through the registered HTTP exec form instead.
 * <p>Idempotency anchor = {@code exec_spec ->> 'intent'}, never the display
 * name. A name squatted by a foreign row is skipped with a warning.
 * <p>Read semantics: the input schema carries no {@code x-write}, so the
 * dialog runs these tools without a confirm ticket; the only model-writable
 * field is the attached-image handle — the identity anchors arrive stamped
 * by the server (and any model-written copy is overwritten before dispatch).
 * No authRef token: the internal doors are unauthenticated pass-through
 * (core's executeHttp treats a missing authRef as none, not as a failure).
 */
@Component
public class OcrDialogToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OcrDialogToolSeedInitializer.class);

    /** Character post tool name (also its intent anchor; mirrors DialogOcrReadAnchor). */
    static final String TOOL_TEXT = "ocr.text_extract";

    /** Grid post tool name (also its intent anchor; mirrors DialogOcrReadAnchor). */
    static final String TOOL_GRID = "ocr.grid_read";

    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_TEXT, TOOL_GRID);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    /** Single exposed field: the handle of an image already attached in this conversation. */
    private static final String SCHEMA_OBJECT_KEY =
            "{\"type\":\"object\",\"properties\":{"
                    + "\"objectKey\":{\"type\":\"string\",\"description\":"
                    + "\"要读取的图片句柄（来自本会话已发送图片的附件句柄，不能自拟地址）\"}}"
                    + ",\"required\":[\"objectKey\"]}";

    private static final String DESC_TEXT =
            "逐行提取会话图片中的文字：每行给出内容、位置框与置信标注，标红行会单独点名；"
                    + "适合把截图里的文字原样抄出来（包括非表格的截图）";

    private static final String DESC_GRID =
            "读取会话中表格或面板截图的行列内容：先识别文字再装配成行列表格，返回表格明细与网格判定；"
                    + "适合看表格、表单类截图里的具体数值";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;
    private final String aiServiceBaseUrl;

    public OcrDialogToolSeedInitializer(ToolService toolService,
                                        JdbcTemplate jdbcTemplate,
                                        @Value("${aiservice.base-url:http://localhost:18084}")
                                                String aiServiceBaseUrl) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
        this.aiServiceBaseUrl = aiServiceBaseUrl;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("seeded dialog OCR tools created={}", created);
            }
        } catch (RuntimeException ex) {
            // A seed failure must not block startup: the tools can also be
            // registered by hand on the tool page with the same two names.
            log.warn("dialog OCR tool seeding skipped (startup continues): {}", ex.getMessage());
        }
    }

    /** Register the pair when missing; returns how many rows this call created (0 = complete). */
    public int seedMissing() {
        List<String> present = jdbcTemplate.queryForList(
                "SELECT exec_spec ->> 'intent' FROM tp_tool WHERE exec_spec ->> 'intent' IS NOT NULL"
                        + " AND exec_spec ->> 'intent' IN ('" + String.join("','", INTENTS) + "')",
                String.class);
        int created = 0;
        for (String intent : INTENTS) {
            if (present.contains(intent)) {
                continue;
            }
            if (nameSquatted(intent)) {
                log.warn("tool name {} is taken by a hand-made row, seed skipped (please reconcile)",
                        intent);
                continue;
            }
            registerPublished(intent);
            created++;
        }
        return created;
    }

    /** True when a row already carries the contract name in the system-level bucket. */
    private boolean nameSquatted(String toolName) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_tool WHERE name = ? AND project_id IS NULL"
                        + " AND (exec_spec ->> 'intent' IS NULL"
                        + " OR exec_spec ->> 'intent' <> ?)",
                Integer.class, toolName, toolName);
        return count != null && count > 0;
    }

    private void registerPublished(String intent) {
        boolean grid = TOOL_GRID.equals(intent);
        String url = trimTrailingSlash(aiServiceBaseUrl) + (grid
                ? "/internal/ocr/grid-read" : "/internal/ocr/text-extract");
        String spec = "{\"url\":\"" + url + "\",\"intent\":\"" + intent + "\"}";
        String description = grid ? DESC_GRID : DESC_TEXT;
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "对话OCR", description, null,
                "[\"对话OCR\", \"内置\"]", SCHEMA_OBJECT_KEY, null,
                ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }

    private static String trimTrailingSlash(String base) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    }
}
