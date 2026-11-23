package com.sixbean.testpike.core.datafactory.domain.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.genkit.GenTemplateResolver;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryCreateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryQuery;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunInfo;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryRunResult;
import com.sixbean.testpike.core.datafactory.api.dto.DataFactoryUpdateInput;
import com.sixbean.testpike.core.datafactory.api.dto.DataPoolItemInfo;
import com.sixbean.testpike.core.datafactory.domain.service.DataFactoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import com.sixbean.testpike.common.genkit.GenFunction;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionGroupInfo;
import com.sixbean.testpike.core.datafactory.api.dto.GenFunctionInfo;
import com.sixbean.testpike.core.audit.domain.component.AuditLogContext;
import com.sixbean.testpike.core.audit.domain.component.AuditSnapshot;


@Service
public class DataFactoryServiceImpl implements DataFactoryService {

    private static final Logger log = LoggerFactory.getLogger(DataFactoryServiceImpl.class);

    private static final String[] TYPE_LABELS = {"", "API", "SQL", "脚本"};

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final ConfigCipher configCipher;
    private final com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder;
    
    private final com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
            batchRuntimeChangeRecorder;
 
    private final com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService resourceLeaseService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    public DataFactoryServiceImpl(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
                                  ConfigCipher configCipher,
                                  com.sixbean.testpike.core.config.domain.component.ConfigCacheHolder configCacheHolder,
                                  com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder
                                          batchRuntimeChangeRecorder,
                                  com.sixbean.testpike.core.lease.domain.service.ResourceLeaseService
                                          resourceLeaseService) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.configCipher = configCipher;
        this.configCacheHolder = configCacheHolder;
        this.batchRuntimeChangeRecorder = batchRuntimeChangeRecorder;
        this.resourceLeaseService = resourceLeaseService;
    }

    
    private int configInt(String key, int defaultValue) {
        try {
            return Integer.parseInt(configCacheHolder.get(key) == null
                    ? String.valueOf(defaultValue) : configCacheHolder.get(key));
        } catch (Exception e) {
            return defaultValue;
        }
    }

    @Override
    @Transactional
    @Audit(objectType = "data_factory", action = "factory_create", objectId = "#result.id()", after = "#input")
    public DataFactoryInfo create(long operatorId, DataFactoryCreateInput input) {
        if (input.type() < 1 || input.type() > 3) {
            throw new ServiceException("数据生成类型取值不合法——请在「自动造数」→「新建造数脚本」弹窗重新选择「造数类型」，正确写法：1=API 2=SQL 3=脚本", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new ServiceException("「脚本名称」不能为空——请在「自动造数」→「新建造数脚本」弹窗填写「脚本名称」后再保存，示例：订单批量造数", BizErrorCode.VALIDATION_ERROR);
        }
        if (input.content() == null || input.content().isBlank()) {
            throw new ServiceException("「造数内容」不能为空——请在「新建造数脚本」弹窗按「造数类型」填写「造数内容」后再保存，示例：SQL 类型填 INSERT 语句（输入框内置有参考样例）", BizErrorCode.VALIDATION_ERROR);
        }
        assertEnvInProject(input.projectId(), input.envId());
        assertPoolKeySavable(input.projectId(), input.envId(),
                poolKeyOf(input.type(), input.content()), null);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO tp_data_factory (project_id, env_id, type, name, content, params_json,
                                             status, created_by)
                VALUES (?, ?, ?, ?, ?, ?, 1, ?) RETURNING id
                """, Long.class, input.projectId(), input.envId(), input.type(),
                input.name().trim(), input.content(), input.paramsJson(), operatorId);
        return get(operatorId, id);
    }

    /**
 * Sort whitelist for the data-factory list . Values are
 * physical columns of tp_data_factory without the alias prefix; the map
 * output is the only thing reaching the ORDER BY clause, enforced
 * fail-closed by the shared sort-column gate. envName/createdByName stay
 * out on purpose: they are join-enriched display columns, not base-table
 * columns of the queried window.
 */
    private static final java.util.Map<String, String> SORT_COLUMNS = java.util.Map.of(
            "id", "id",
            "name", "name",
            "type", "type",
            "status", "status",
            "created_at", "created_at");

    /**
     * ORDER BY builder for the factory window: a null/blank key keeps the
     * historical id DESC default; a whitelisted key orders by that column
     * (always alias-prefixed, the query reads FROM tp_data_factory f) with the
     * constant id DESC tie-break pinned; an off-list key is refused
     * fail-closed by the gate.
     */
    private String orderClause(String sortBy, String sortDir) {
        String column = com.sixbean.testpike.core.common.domain.component.SortColumnPolicy
                .require(sortBy, SORT_COLUMNS, "数据工厂");
        if (column == null) {
            return " ORDER BY f.id DESC";
        }
        return " ORDER BY f." + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", f.id DESC";
    }

    @Override
    public PageResult<DataFactoryInfo> list(long operatorId, DataFactoryQuery query) {
        int page = Math.max(1, query.page());
        int size = Math.min(200, Math.max(1, query.size() == 0 ? 20 : query.size()));
        // Fail fast on an off-list sort key before touching the database.
        String order = orderClause(query.sortBy(), query.sortDir());
        StringBuilder where = new StringBuilder(" WHERE f.project_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.projectId());
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND f.name ILIKE ?");
            args.add("%" + query.keyword().trim() + "%");
        }
        if (query.type() != null && query.type() >= 1 && query.type() <= 3) {
            where.append(" AND f.type = ?");
            args.add(query.type());
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_factory f" + where, Long.class, args.toArray());
        args.add(size);
        args.add((page - 1) * size);
        List<DataFactoryInfo> rows = jdbcTemplate.query("""
                SELECT f.id, f.project_id, f.env_id, e.name AS env_name, f.type, f.name, f.content,
                       f.params_json, f.status, COALESCE(u.display_name, '') AS created_by_name,
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_data_factory f
                LEFT JOIN tp_project_env e ON e.id = f.env_id
                LEFT JOIN tp_user u ON u.id = f.created_by
                %s%s LIMIT ? OFFSET ?
                """.formatted(where, order), factoryRowMapper(), args.toArray());
        return new PageResult<>(rows, total);
    }

    private org.springframework.jdbc.core.RowMapper<DataFactoryInfo> factoryRowMapper() {
        return (rs, rowNum) -> new DataFactoryInfo(rs.getLong("id"), rs.getLong("project_id"),
                rs.getLong("env_id"), rs.getString("env_name"), rs.getInt("type"),
                rs.getString("name"), rs.getString("content"), rs.getString("params_json"),
                rs.getInt("status"), rs.getString("created_by_name"), rs.getString("created_at"));
    }

    @Override
    public Integer currentVersion(long id) {
        List<Integer> rows = jdbcTemplate.queryForList(
                "SELECT version FROM tp_data_factory WHERE id = ?", Integer.class, id);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public DataFactoryInfo get(long operatorId, long id) {
        List<DataFactoryInfo> rows = jdbcTemplate.query("""
                SELECT f.id, f.project_id, f.env_id, e.name AS env_name, f.type, f.name, f.content,
                       f.params_json, f.status, COALESCE(u.display_name, '') AS created_by_name,
                       to_char(f.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_data_factory f
                LEFT JOIN tp_project_env e ON e.id = f.env_id
                LEFT JOIN tp_user u ON u.id = f.created_by
                WHERE f.id = ?
                """, factoryRowMapper(), id);
        if (rows.isEmpty()) {
            throw new ServiceException("造数脚本（id=" + id + "）不存在——请到「自动造数」→「造数脚本」列表确认该脚本未被删除，建议从列表重新进入",
                    BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    @Override
    @Transactional
    @Audit(objectType = "data_factory", action = "factory_update", objectId = "#id", after = "#input")
    public void update(long operatorId, long id, DataFactoryUpdateInput input) {
        DataFactoryInfo current = get(operatorId, id);
        AuditLogContext.setBefore(AuditSnapshot.snapshot(current));
        StringBuilder set = new StringBuilder(" SET updated_at = now()");
        List<Object> args = new ArrayList<>();
        if (input.name() != null && !input.name().isBlank()) {
            set.append(", name = ?");
            args.add(input.name().trim());
        }
        if (input.content() != null && !input.content().isBlank()) {
            assertPoolKeySavable(current.projectId(), current.envId(),
                    poolKeyOf(current.type(), input.content()), id);
            set.append(", content = ?");
            args.add(input.content());
        }
        if (input.status() != null && (input.status() == 1 || input.status() == 2)) {
            set.append(", status = ?");
            args.add(input.status());
        }
        if (input.paramsJson() != null) {
            set.append(", params_json = ?");
            args.add(input.paramsJson());
        }
        // AM batch every write through this chain bumps the row
        // version — the dialog draft anchor (V330) re-reads it at landing.
        set.append(", version = version + 1");
        args.add(id);
        jdbcTemplate.update("UPDATE tp_data_factory" + set + " WHERE id = ?", args.toArray());
    }

    @Override
    @Transactional
    @Audit(objectType = "data_factory", action = "factory_delete", objectId = "#id")
    public void delete(long operatorId, long id) {
        get(operatorId, id);
        jdbcTemplate.update("DELETE FROM tp_data_factory_run WHERE factory_id = ?", id);
        jdbcTemplate.update("DELETE FROM tp_data_factory WHERE id = ?", id);
    }

    @Override
    public DataFactoryRunResult run(long operatorId, long id) {
        DataFactoryInfo factory = get(operatorId, id);
        if (factory.status() != 1) {
            throw new ServiceException("造数脚本已停用，无法执行——请在「自动造数」→「造数脚本」列表对该脚本「编辑」，将状态改回「启用」后再点「执行」", BizErrorCode.STATE_MACHINE_REJECT);
        }
        long runId = jdbcTemplate.queryForObject("""
                INSERT INTO tp_data_factory_run (factory_id, status, created_by)
                VALUES (?, 1, ?) RETURNING id
                """, Long.class, id, operatorId);
        List<Map<String, Object>> genSeeds = new ArrayList<>();
        try {
            List<String> values = execute(factory, genSeeds);
            int poolCount = writePool(factory, values, operatorId);
            String summary = "造了 " + values.size() + " 条（入池 " + poolCount + " 条）";
            jdbcTemplate.update("UPDATE tp_data_factory_run SET status = 2, result_summary = ?, gen_seeds = ?::jsonb WHERE id = ?",
                    summary, genSeeds.isEmpty() ? null : GenTemplateResolver.seedsAsJson(genSeeds), runId);
            return new DataFactoryRunResult(id, 2, values.size(), poolCount, summary);
        } catch (Exception e) {
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            String summary = "执行失败：" + reason;
            jdbcTemplate.update("UPDATE tp_data_factory_run SET status = 3, result_summary = ?, gen_seeds = ?::jsonb WHERE id = ?",
                    summary, genSeeds.isEmpty() ? null : GenTemplateResolver.seedsAsJson(genSeeds), runId);
            log.warn("造数执行失败 factoryId={}: {}", id, reason);
            return new DataFactoryRunResult(id, 3, 0, 0, summary);
        }
    }

    
    private List<String> execute(DataFactoryInfo factory, List<Map<String, Object>> genSeeds) throws Exception {
        return switch (factory.type()) {
            case 1 -> executeApi(factory.content(), genSeeds);
            case 2 -> executeSql(factory.content(), genSeeds);
            case 3 -> executeScript(factory.content());
            default -> List.of();
        };
    }


    private List<String> executeApi(String content, List<Map<String, Object>> genSeeds) throws Exception {
        JsonNode def = objectMapper.readTree(content);
        String method = def.path("method").asText("GET").toUpperCase();
        String url = GenTemplateResolver.resolve(def.path("url").asText(), genSeeds);
        if (url.isBlank()) {
            throw new ServiceException("API 造数内容缺少 url 字段——请在「造数内容」的 JSON 中补充 url，示例：\"url\": \"https://api.example.com/orders\"");
        }
        String extractExpr = def.path("extractExpr").asText("$.data");
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        JsonNode headers = def.path("headers");
        if (headers.isObject()) {
            headers.fields().forEachRemaining(e ->
                    builder.header(e.getKey(), GenTemplateResolver.resolve(e.getValue().asText(), genSeeds)));
        }
        if ("POST".equals(method) || "PUT".equals(method)) {
            JsonNode body = def.path("body");
            builder.method(method, HttpRequest.BodyPublishers.ofString(
                    body.isMissingNode() || body.isNull() ? "{}" : resolveNode(body, genSeeds).toString(),
                    StandardCharsets.UTF_8));
        } else {
            builder.GET();
        }
        HttpResponse<String> resp = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() >= 400) {
            throw new ServiceException("API 造数请求失败 HTTP " + resp.statusCode()
                    + "：" + truncate(resp.body(), 200)
                    + "——请核对「造数内容」中的 url、headers、body 与目标接口是否一致，修正后重新点「执行」");
        }
        JsonNode root = objectMapper.readTree(resp.body());
        return extractValues(root, extractExpr);
    }

    
    private JsonNode resolveNode(JsonNode node, List<Map<String, Object>> genSeeds) {
        if (node.isTextual()) {
            return objectMapper.getNodeFactory().textNode(
                    GenTemplateResolver.resolve(node.asText(), genSeeds));
        }
        if (node.isObject()) {
            ObjectNode out = objectMapper.createObjectNode();
            node.fields().forEachRemaining(e -> out.set(e.getKey(), resolveNode(e.getValue(), genSeeds)));
            return out;
        }
        if (node.isArray()) {
            ArrayNode out = objectMapper.createArrayNode();
            node.forEach(item -> out.add(resolveNode(item, genSeeds)));
            return out;
        }
        return node;
    }

    
    public static List<String> extractValues(JsonNode root, String expr) {
        List<String> out = new ArrayList<>();
        String path = expr.trim();
        if (path.startsWith("$")) {
            path = path.substring(1);
        }
        if (path.startsWith(".")) {
            path = path.substring(1);
        }
        collect(root, path, out);
        return out;
    }

    private static void collect(JsonNode node, String path, List<String> out) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return;
        }
        if (path.isBlank()) {
            addLeaf(node, out);
            return;
        }
        String segment;
        String rest;
        int dot = path.indexOf('.');
        if (dot >= 0) {
            segment = path.substring(0, dot);
            rest = path.substring(dot + 1);
        } else {
            segment = path;
            rest = "";
        }
        if (segment.equals("[*]") || segment.equals("*")) {
            if (node.isArray()) {
                for (JsonNode item : node) {
                    collect(item, rest, out);
                }
            }
            return;
        }
        String name = segment.replaceFirst("^\\[(\\d+)]$", "$1");
        boolean index = segment.matches("^\\[\\d+]$");
        if (index) {
            int idx = Integer.parseInt(name);
            if (node.isArray() && idx < node.size()) {
                collect(node.get(idx), rest, out);
            }
            return;
        }
        boolean arrayAll = segment.endsWith("[*]");
        String field = arrayAll ? segment.substring(0, segment.length() - 3) : segment;
        JsonNode child = node.get(field);
        if (arrayAll && child != null && child.isArray()) {
            for (JsonNode item : child) {
                collect(item, rest, out);
            }
        } else {
            collect(child, rest, out);
        }
    }

    private static void addLeaf(JsonNode node, List<String> out) {
        if (node.isArray()) {
            for (JsonNode item : node) {
                addLeaf(item, out);
            }
        } else if (node.isValueNode()) {
            String v = node.asText();
            if (!v.isBlank()) {
                out.add(v);
            }
        } else if (node.isObject()) {
            node.fields().forEachRemaining(e -> {
                if (e.getValue().isValueNode()) {
                    out.add(e.getValue().asText());
                }
            });
        }
    }


    private List<String> executeSql(String content, List<Map<String, Object>> genSeeds) throws Exception {
        JsonNode def = objectMapper.readTree(content);
        String sql = GenTemplateResolver.resolve(def.path("sql").asText(), genSeeds);
        if (sql.isBlank()) {
            throw new ServiceException("SQL 造数内容缺少 sql 字段——请在「造数内容」的 JSON 中填写 sql，示例：\"sql\": \"INSERT INTO tp_order (name) VALUES (\'x\') RETURNING id\"");
        }
        String trimmed = sql.trim().toLowerCase();
        if (!trimmed.startsWith("insert") && !trimmed.startsWith("update")
                && !trimmed.startsWith("delete")) {
            throw new ServiceException("SQL 造数仅支持 INSERT/UPDATE/DELETE（禁止任意查询执行）——请把「造数内容」中的 sql 语句改为上述三种之一开头，示例：INSERT INTO tp_order (name) VALUES ('x') RETURNING id");
        }
        List<String> out = new ArrayList<>();
        if (trimmed.contains("returning")) {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql);
            for (Map<String, Object> row : rows) {
                Object first = row.values().iterator().next();
                if (first != null) {
                    out.add(String.valueOf(first));
                }
            }
        } else {
            int affected = jdbcTemplate.update(sql);
            if (affected > 0) {
                out.add("affected:" + affected);
            }
        }
        return out;
    }


    private List<String> executeScript(String content) throws Exception {
        String poolType = "订单";
        String script = content;
        for (String line : content.split("\n")) {
            if (line.startsWith("# poolType=")) {
                poolType = line.substring("# poolType=".length()).trim();
                script = content.replace(line + "\n", "");
                break;
            }
        }
        Path tmp = Files.createTempFile("tp-factory-", ".py");
        try {
            Files.writeString(tmp, script, StandardCharsets.UTF_8);
            Process p = new ProcessBuilder("python3", tmp.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = p.waitFor(30, TimeUnit.SECONDS);
            String output = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!finished) {
                p.destroyForcibly();
                throw new ServiceException("脚本造数执行超时（超过 30 秒未结束）——请精简脚本逻辑使单次执行在 30 秒内完成，或在「造数类型」改用 API/SQL 造数");
            }
            if (p.exitValue() != 0) {
                throw new ServiceException("脚本造数执行失败（exit " + p.exitValue() + "）："
                        + truncate(output, 300)
                        + "——请根据上方脚本输出修正「造数内容」中的 Python 脚本后重新点「执行」");
            }
            JsonNode arr = objectMapper.readTree(output);
            List<String> values = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode item : arr) {
                    if (item.isValueNode() && !item.asText().isBlank()) {
                        values.add(item.asText());
                    }
                }
            }
            if (values.isEmpty()) {
                throw new ServiceException("脚本造数未输出有效 JSON 数组——请让脚本向 stdout 只输出 JSON 数组，示例：[\"acc1\",\"acc2\"]，除数组外不要打印其它内容");
            }
            return values;
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    
    private int writePool(DataFactoryInfo factory, List<String> values, long operatorId) {
        String poolType = poolTypeOf(factory);
        String poolKey = poolKeyOf(factory.type(), factory.content());
        boolean keyFree = poolKey == null || jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_pool_item WHERE project_id = ? AND env_id = ? AND name = ?",
                Long.class, factory.projectId(), factory.envId(), poolKey) == 0;
        int inserted = 0;
        boolean keyWritten = false;
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String hash = sha256(v);
            String encrypted = configCipher.isEnabled() ? configCipher.encrypt(v) : v;
            String name = keyFree && !keyWritten ? poolKey : null;
            int rows = jdbcTemplate.update("""
                    INSERT INTO tp_data_pool_item (project_id, env_id, type, value, value_hash, name, status, created_by)
                    VALUES (?, ?, ?, ?, ?, ?, 1, ?)
                    ON CONFLICT (env_id, type, value_hash) DO NOTHING
                    """, factory.projectId(), factory.envId(), poolType, encrypted, hash, name, operatorId);
            inserted += rows;
            if (rows > 0 && name != null) {
                keyWritten = true;
            }
        }
        if (inserted > 0) {
            batchRuntimeChangeRecorder.recordEnvChange(factory.envId(),
                    com.sixbean.testpike.core.batch.domain.component.BatchRuntimeChangeRecorder.KIND_POOL);
        }
        return inserted;
    }

    
    private String poolTypeOf(DataFactoryInfo factory) {
        try {
            if (factory.type() == 3) {
                for (String line : factory.content().split("\n")) {
                    if (line.startsWith("# poolType=")) {
                        return line.substring("# poolType=".length()).trim();
                    }
                }
                return "订单";
            }
            JsonNode def = objectMapper.readTree(factory.content());
            String t = def.path("poolType").asText("订单");
            return t.isBlank() ? "订单" : t.trim();
        } catch (Exception e) {
            return "订单";
        }
    }


    
    private static final java.util.regex.Pattern POOL_KEY_PATTERN =
            java.util.regex.Pattern.compile("^[A-Za-z_][A-Za-z0-9_]{0,63}$");

    
    private String poolKeyOf(int type, String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            if (type == 3) {
                for (String line : content.split("\n")) {
                    if (line.startsWith("# poolKey=")) {
                        String k = line.substring("# poolKey=".length()).trim();
                        return k.isBlank() ? null : k;
                    }
                }
                return null;
            }
            JsonNode def = objectMapper.readTree(content);
            JsonNode k = def.path("poolKey");
            if (k.isMissingNode() || k.isNull()) {
                return null;
            }
            String key = k.asText().trim();
            return key.isBlank() ? null : key;
        } catch (Exception e) {
            return null;
        }
    }

    
    private void assertPoolKeySavable(long projectId, long envId, String key, Long selfFactoryId) {
        if (key == null) {
            return;
        }
        if (!POOL_KEY_PATTERN.matcher(key).matches()) {
            throw new ServiceException("数据键名「" + key + "」不合法：仅允许字母或下划线开头的标识符"
                    + "（字母/数字/下划线，数字不能开头，≤64 字），请更换键名，示例：order_pool_1", BizErrorCode.VALIDATION_ERROR);
        }
        List<java.util.Map<String, Object>> rows = selfFactoryId == null
                ? jdbcTemplate.queryForList("SELECT type, content FROM tp_data_factory"
                        + " WHERE project_id = ? AND env_id = ?", projectId, envId)
                : jdbcTemplate.queryForList("SELECT type, content FROM tp_data_factory"
                        + " WHERE project_id = ? AND env_id = ? AND id <> ?", projectId, envId, selfFactoryId);
        for (java.util.Map<String, Object> row : rows) {
            String other = poolKeyOf(((Number) row.get("type")).intValue(),
                    (String) row.get("content"));
            if (key.equals(other)) {
                throw new ServiceException("数据键名「" + key + "」已被该项目环境下的其他造数脚本占用："
                        + "键名在项目+环境内必须唯一，请换一个键名（示例：order_pool_2），或到「自动造数」→「造数脚本」列表编辑占用该键名的脚本为其改名", BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    @Override
    public PageResult<DataFactoryRunInfo> runs(long operatorId, long projectId, long factoryId,
                                               int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size == 0 ? 20 : size));
        DataFactoryInfo factory = get(operatorId, factoryId);
        if (factory.projectId() != projectId) {
            throw new ServiceException("造数脚本（id=" + factoryId + "）不存在或不属于当前项目——请在顶栏「所属项目」切换到脚本所属项目后重试",
                    BizErrorCode.NOT_FOUND);
        }
        long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_factory_run WHERE factory_id = ?", Long.class, factoryId);
        List<DataFactoryRunInfo> rows = jdbcTemplate.query("""
                SELECT r.id, r.factory_id, r.status, r.result_summary,
                       COALESCE(u.display_name, '') AS created_by_name,
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_data_factory_run r
                LEFT JOIN tp_user u ON u.id = r.created_by
                WHERE r.factory_id = ? ORDER BY r.id DESC LIMIT ? OFFSET ?
                """, runRowMapper(), factoryId, s, (p - 1) * s);
        return new PageResult<>(rows, total);
    }

    @Override
    public List<DataFactoryRunInfo> recentRuns(long operatorId, long projectId, long factoryId, int limit) {
        DataFactoryInfo factory = get(operatorId, factoryId);
        if (factory.projectId() != projectId) {
            throw new ServiceException("造数脚本（id=" + factoryId + "）不存在或不属于当前项目——请在顶栏「所属项目」切换到脚本所属项目后重试",
                    BizErrorCode.NOT_FOUND);
        }
        return jdbcTemplate.query("""
                SELECT r.id, r.factory_id, r.status, r.result_summary,
                       COALESCE(u.display_name, '') AS created_by_name,
                       to_char(r.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_data_factory_run r
                LEFT JOIN tp_user u ON u.id = r.created_by
                WHERE r.factory_id = ? ORDER BY r.id DESC LIMIT ?
                """, runRowMapper(), factoryId, limit);
    }


    @Override
    public List<GenFunctionGroupInfo> genkitCatalog() {
        return GenFunction.groupedCatalog().entrySet().stream()
                .map(e -> new GenFunctionGroupInfo(e.getKey(), e.getValue().stream()
                        .map(f -> new GenFunctionInfo(f.functionName(), f.description(),
                                f.paramSchema(), f.example()))
                        .toList()))
                .toList();
    }


    @Override
    public PageResult<DataPoolItemInfo> listPoolItems(long operatorId, long projectId, Long envId,
                                                      String type, Integer status, int page, int size) {
        int p = Math.max(1, page);
        int s = Math.min(100, Math.max(1, size == 0 ? 20 : size));
        if (envId != null) {
            assertEnvInProject(projectId, envId);
        }
        StringBuilder where = new StringBuilder("WHERE i.project_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(projectId);
        if (envId != null) {
            where.append(" AND i.env_id = ?");
            args.add(envId);
        }
        if (type != null && !type.isBlank()) {
            where.append(" AND i.type = ?");
            args.add(type.trim());
        }
        if (status != null) {
            where.append(" AND i.status = ?");
            args.add(status);
        }
        Object[] params = args.toArray();
        Long total = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM tp_data_pool_item i " + where, Long.class, params);
        Object[] pageParams = java.util.Arrays.copyOf(params, params.length + 2);
        pageParams[params.length] = s;
        pageParams[params.length + 1] = (p - 1) * s;
        java.util.List<DataPoolItemInfo> rows = jdbcTemplate.query("""
                SELECT i.id, i.project_id, i.env_id, COALESCE(e.name, '') AS env_name,
                       i.type, i.value, i.name, i.status, i.created_by,
                       to_char(i.created_at, 'YYYY-MM-DD HH24:MI:SS') AS created_at
                FROM tp_data_pool_item i
                LEFT JOIN tp_project_env e ON e.id = i.env_id
                """ + where + " ORDER BY i.id DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new DataPoolItemInfo(rs.getLong("id"), rs.getLong("project_id"),
                        rs.getLong("env_id"), rs.getString("env_name"), rs.getString("type"),
                        decryptValue(rs.getString("value")), rs.getString("name"), rs.getInt("status"),
                        POOL_STATUS_LABELS.getOrDefault(rs.getInt("status"), "未知"),
                        rs.getString("created_at"), rs.getLong("created_by") == 0
                        && rs.wasNull() ? null : rs.getLong("created_by")),
                pageParams);
        return new PageResult<>(rows, total);
    }

    @Override
    public void updatePoolItemStatus(long operatorId, long projectId, long id, int status) {
        if (status < 1 || status > 3) {
            throw new ServiceException("数据池状态取值非法（传入 " + status + "）——状态仅支持 1空闲 2占用中 3已失效，建议在「自动造数」→「数据池」列表用「回收为空闲」「标记失效」按钮操作", BizErrorCode.VALIDATION_ERROR);
        }
        assertPoolItemInProject(projectId, id);
        jdbcTemplate.update("UPDATE tp_data_pool_item SET status = ? WHERE id = ?", status, id);
    }

    @Override
    public void deletePoolItem(long operatorId, long projectId, long id) {
        assertPoolItemInProject(projectId, id);
        jdbcTemplate.update("DELETE FROM tp_data_pool_item WHERE id = ?", id);
    }


    @Override
    @Transactional
    @Audit(objectType = "data_pool_item", action = "pool_occupy", objectId = "#itemId", after = "#lockedBy")
    public void occupy(long operatorId, long projectId, long itemId, long lockedBy) {
        assertPoolItemInProject(projectId, itemId);
        int status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_data_pool_item WHERE id = ?", Integer.class, itemId);
        if (status == 3) {
            throw new ServiceException("数据项已失效，无法占用——请到「自动造数」→「数据池」列表选择「池状态」为空闲的数据项", BizErrorCode.RESOURCE_CONFLICT);
        }
        try {
            resourceLeaseService.acquire("data", String.valueOf(itemId), projectId, null, null,
                    String.valueOf(lockedBy));
        } catch (ServiceException e) {
            throw new ServiceException("数据项已被占用，请稍后重试，或到「自动造数」→「数据池」列表选择其他「池状态」为空闲的数据项", BizErrorCode.STATE_MACHINE_REJECT);
        }
        jdbcTemplate.update(
                "UPDATE tp_data_pool_item SET status = 2, locked_by = ?, locked_at = now() WHERE id = ?",
                lockedBy, itemId);
    }

    @Override
    @Transactional
    @Audit(objectType = "data_pool_item", action = "pool_release", objectId = "#itemId")
    public void release(long operatorId, long projectId, long itemId) {
        assertPoolItemInProject(projectId, itemId);
        int status = jdbcTemplate.queryForObject(
                "SELECT status FROM tp_data_pool_item WHERE id = ?", Integer.class, itemId);
        if (status == 3) {
            throw new ServiceException("数据项已失效，无法释放——请到「自动造数」→「数据池」列表核对该项「池状态」，已失效数据项无需释放", BizErrorCode.RESOURCE_CONFLICT);
        }
        String leaseHolderRef = currentLockedBy(itemId);
        if (leaseHolderRef != null) {
            resourceLeaseService.release("data", String.valueOf(itemId), leaseHolderRef);
        }
        jdbcTemplate.update("""
                UPDATE tp_data_pool_item SET status = 1, locked_by = NULL, locked_at = NULL
                WHERE id = ? AND status = 2
                """, itemId);
    }

    
    private String currentLockedBy(long itemId) {
        List<Long> locked = jdbcTemplate.queryForList(
                "SELECT locked_by FROM tp_data_pool_item WHERE id = ?", Long.class, itemId);
        return locked.isEmpty() || locked.get(0) == null ? null : String.valueOf(locked.get(0));
    }

    @Override
    public int releaseExpired() {
        int timeoutMinutes = configInt("testpike.pool.lock-timeout-minutes", 30);
        List<Map<String, Object>> expired = jdbcTemplate.queryForList("""
                SELECT id, locked_by FROM tp_data_pool_item
                WHERE status = 2 AND locked_at IS NOT NULL
                  AND locked_at < now() - make_interval(mins => ?)
                """, timeoutMinutes);
        if (expired.isEmpty()) {
            return 0;
        }
        for (Map<String, Object> row : expired) {
            String holder = row.get("locked_by") == null ? null : String.valueOf(row.get("locked_by"));
            if (holder != null) {
                resourceLeaseService.release("data", String.valueOf(row.get("id")), holder);
            }
        }
        return jdbcTemplate.update("""
                UPDATE tp_data_pool_item SET status = 1, locked_by = NULL, locked_at = NULL
                WHERE status = 2 AND locked_at IS NOT NULL
                  AND locked_at < now() - make_interval(mins => ?)
                """, timeoutMinutes);
    }

    @Override
    public int cleanInvalid() {
        return jdbcTemplate.update("DELETE FROM tp_data_pool_item WHERE status = 3");
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000)
    public void sweepPool() {
        try {
            int released = releaseExpired();
            int cleaned = cleanInvalid();
            if (released > 0 || cleaned > 0) {
                log.info("数据池定时维护：超时释放 {} 条，清理失效 {} 条", released, cleaned);
            }
        } catch (Exception e) {
            log.warn("数据池定时维护失败: {}", e.getMessage());
        }
    }

    
    private void assertPoolItemInProject(long projectId, long id) {
        List<Long> project = jdbcTemplate.queryForList(
                "SELECT project_id FROM tp_data_pool_item WHERE id = ?", Long.class, id);
        if (project.isEmpty() || project.get(0) != projectId) {
            throw new ServiceException("数据池项（id=" + id + "）不存在或不属于当前项目——请到「自动造数」→「数据池」列表确认，必要时在顶栏「所属项目」切换项目", BizErrorCode.NOT_FOUND);
        }
    }

    private static final java.util.Map<Integer, String> POOL_STATUS_LABELS = java.util.Map.of(
            1, "空闲", 2, "占用中", 3, "已失效");

    private String decryptValue(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return "";
        }
        try {
            return configCipher.isEnabled() ? configCipher.decrypt(encrypted) : encrypted;
        } catch (Exception e) {
            log.warn("数据池值解密失败（保留密文展示）: {}", e.getMessage());
            return encrypted;
        }
    }

    private org.springframework.jdbc.core.RowMapper<DataFactoryRunInfo> runRowMapper() {
        return (rs, rowNum) -> new DataFactoryRunInfo(rs.getLong("id"), rs.getLong("factory_id"),
                rs.getInt("status"), rs.getString("result_summary"),
                rs.getString("created_by_name"), rs.getString("created_at"));
    }

    private void assertEnvInProject(long projectId, long envId) {
        List<Long> owner = jdbcTemplate.queryForList(
                "SELECT project_id FROM tp_project_env WHERE id = ?", Long.class, envId);
        if (owner.isEmpty()) {
            throw new ServiceException("所选环境不存在——请在「新建造数脚本」弹窗的「目标环境」下拉重新选择本项目已配置的环境", BizErrorCode.NOT_FOUND);
        }
        if (owner.get(0) != projectId) {
            throw new ServiceException("所选环境不属于当前项目——请在顶栏「所属项目」切换到该环境所属项目，或在「目标环境」下拉改选本项目的环境", BizErrorCode.VALIDATION_ERROR);
        }
    }

    private static String sha256(String value) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    private static String truncate(String s, int max) {
        return s == null ? "" : (s.length() <= max ? s : s.substring(0, max) + "…");
    }
}
