package com.sixbean.testpike.core.apiaccess.domain.component;

import com.sixbean.testpike.core.apiaccess.domain.service.ApiAccessService;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.domain.component.ToolExecKind;
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
 * Registers the two generic dialog tools that expose the pass-through door: a catalog search and a
 * read. Both are HTTP-form tools whose URL is dialed for real, and both are read-shaped (no
 * {@code x-write}), so the dispatcher routes the call straight here instead of demanding a
 * confirmation ticket.
 *
 * <p>Why seed only two rows instead of one per capability: the endpoint catalog is generated from
 * the live request mappings, so a newly added read endpoint becomes reachable in the same release
 * without anybody registering a tool. Coverage grows with the product, not with somebody's backlog.
 *
 * <p>Registration runs the same approval chain as every other tool (create → submit → approve), and
 * the idempotency anchor is {@code exec_spec ->> 'intent'}: a re-start creates nothing, and a name
 * already squatted by a hand-made row is skipped with a warning instead of being overwritten.
 */
@Component
public class ApiAccessToolSeedInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ApiAccessToolSeedInitializer.class);

    /** Catalog search tool name (also its intent anchor). */
    static final String TOOL_CATALOG = "apiAccess.catalog";

    /** Pass-through read tool name (also its intent anchor). */
    static final String TOOL_READ = "apiAccess.read";

    /**
     * Pass-through change tool name (also its intent anchor). Carries {@code x-write} in its schema
     * and a {@code draftObjectType} in its exec spec, so the dialog never executes it: the call
     * becomes a confirmable card, and the core landing strategy is the only thing that can write.
     */
    static final String TOOL_WRITE = "apiAccess.write";

    /** Draft kind token the landing strategy routes on (mirror of core's API_ACCESS_WRITE). */
    static final String DRAFT_OBJECT_TYPE = "API_ACCESS_WRITE";

    /** Intent list doubles as the idempotency anchor set (exec_spec->>'intent'). */
    static final List<String> INTENTS = List.of(TOOL_CATALOG, TOOL_READ, TOOL_WRITE);

    private static final long SEED_OPERATOR_ID = 1L;
    private static final long SEED_APPROVER_ID = 2L;

    private static final String SCHEMA_CATALOG =
            "{\"type\":\"object\",\"properties\":{"
            + "\"keyword\":{\"type\":\"string\",\"description\":\"按接口路径、参数名或登记说明检索目录（留空=列出可直读条目；条目总数超过阈值时交付按域汇总的索引，把索引行中的域前缀填入 keyword 重新检索即可下钻该域明细）\"},"
            + "\"onlyReachable\":{\"type\":\"boolean\",\"description\":\"只列当前可直读的条目（缺省 true；false 时同时列出被拦条目及其拦截原因）\"}}}";

    private static final String SCHEMA_READ =
            "{\"type\":\"object\",\"properties\":{"
            + "\"endpointKey\":{\"type\":\"string\",\"description\":\"目录条目名，形如 \\\"GET /internal/tags\\\"（必须先查目录，不能自拟地址）\"},"
            + "\"arguments\":{\"type\":\"object\",\"description\":\"只允许填该条目声明的参数（未声明参数会被拒绝）\"}},"
            + "\"required\":[\"endpointKey\"]}";

    private static final String SCHEMA_WRITE =
            "{\"type\":\"object\",\"x-write\":true,\"properties\":{"
            + "\"endpointKey\":{\"type\":\"string\",\"description\":\"要执行的变更条目名，形如 \\\"POST /internal/tags\\\"（须先查目录；只读条目与未登记核验的条目不会执行）\"},"
            + "\"arguments\":{\"type\":\"object\",\"description\":\"该条目声明的参数与请求体字段；归属项目由服务端按会话改写，载荷指定无效\"}},"
            + "\"required\":[\"endpointKey\"]}";

    private final ToolService toolService;
    private final JdbcTemplate jdbcTemplate;
    private final ApiAccessService apiAccessService;
    private final int serverPort;

    public ApiAccessToolSeedInitializer(ToolService toolService,
                                       JdbcTemplate jdbcTemplate,
                                       ApiAccessService apiAccessService,
                                       @Value("${server.port:8083}") int serverPort) {
        this.toolService = toolService;
        this.jdbcTemplate = jdbcTemplate;
        this.apiAccessService = apiAccessService;
        this.serverPort = serverPort;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int created = seedMissing();
            if (created > 0) {
                log.info("seeded api pass-through tools created={}", created);
            }
            log.info("api pass-through door ready catalogEntries={}",
                    apiAccessService.catalog(null, false).size());
        } catch (RuntimeException ex) {
            // A seed failure must not block startup: the tools can also be registered by hand on
            // the tool page, and the door itself stays usable for any caller that names an entry.
            log.warn("api pass-through tool seeding skipped (startup continues): {}", ex.getMessage());
        }
    }

    /** Register the generic tools when missing; returns how many rows this call created (0 = complete). */
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
                log.warn("tool name {} is taken by a hand-made row, seed skipped (please reconcile)", intent);
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
        String url = "http://localhost:" + serverPort + "/internal/api-access/"
                + (TOOL_CATALOG.equals(intent) ? "endpoints" : (TOOL_READ.equals(intent) ? "read" : "write"));
        // The write shape is never dialed: x-write makes the dispatcher refuse a token-less call and
        // the orchestrator journal a card instead, so the url is a form-validity placeholder kept
        // truthful (it names the door a confirmed landing goes through).
        String spec = "{\"url\":\"" + url + "\",\"intent\":\"" + intent + "\""
                + (TOOL_WRITE.equals(intent)
                        ? ",\"draftObjectType\":\"" + DRAFT_OBJECT_TYPE + "\"" : "") + "}";
        boolean catalog = TOOL_CATALOG.equals(intent);
        boolean write = TOOL_WRITE.equals(intent);
        String schema = catalog ? SCHEMA_CATALOG : (write ? SCHEMA_WRITE : SCHEMA_READ);
        String description = catalog
                ? "检索本项目接口目录：列出可直读的接口条目、其参数与拦截原因；空词浏览条目过多时按域汇总交付索引（一行一域=前缀+条数+下钻方法），用域前缀作关键词即可下钻明细（对话代读的入口，不含任何写操作）"
                : (write
                    ? "按目录条目名发起本项目接口变更（生成草案卡，确认后才执行；未登记核验的条目与只读条目不会执行）"
                    : "按目录条目名直读本项目一个接口的数据（只读；参数只能填该条目声明的字段；变更类接口不由此门执行）");
        var info = toolService.create(SEED_OPERATOR_ID, new ToolCreateInput(
                null, intent, 2, "系统直通", description, null,
                "[\"接口直通\", \"内置\"]", schema, null,
                ToolExecKind.HTTP.code(), spec, 30000, null));
        var submitted = toolService.submit(SEED_OPERATOR_ID, info.id(), info.version());
        toolService.approve(SEED_APPROVER_ID, submitted.id(), submitted.version());
    }
}
