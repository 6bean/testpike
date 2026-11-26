package com.sixbean.testpike.core.executor.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.util.SnapshotJsonSanitizer;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;


@Component
public class ExecStepSnapshotSupport {

    private static final int DEFAULT_BUDGET_BYTES = 262144;
    private static final int BUDGET_MIN = 1024;
    private static final int BUDGET_MAX = 10485760;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigService configService;
    private final ObjectMapper objectMapper;

    public ExecStepSnapshotSupport(JdbcTemplate jdbcTemplate, ConfigService configService,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    
    public void validate(Object protocolSnapshots) {
        if (protocolSnapshots == null) {
            return;
        }
        if (!(protocolSnapshots instanceof List<?> list)) {
            throw new ServiceException("protocolSnapshots 必须是数组（03 v2.40 result 尾追键）"
                    + "——请执行器按协议以数组形态上报（格式：result 内 protocolSnapshots=[{...}]）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> element) || !element.containsKey("stepNo")) {
                throw new ServiceException("protocolSnapshots 元素必须含 stepNo（03 v2.40）"
                        + "——请补齐每个快照元素的步骤号后重传（格式：\"stepNo\": 1）",
                        BizErrorCode.VALIDATION_ERROR);
            }
            if (!(element.get("stepNo") instanceof Number)) {
                throw new ServiceException("protocolSnapshots 元素 stepNo 必须是数字（03 v2.40）"
                        + "——请去掉步骤号的引号改为数字（示例：\"stepNo\": 1 而非 \"stepNo\": \"1\"）",
                        BizErrorCode.VALIDATION_ERROR);
            }
        }
    }

    
    public void saveSnapshots(long taskId, Object protocolSnapshots) {
        if (!(protocolSnapshots instanceof List<?> list) || list.isEmpty()) {
            return;
        }
        int budget = budgetBytes();
        for (Object item : list) {
            Map<?, ?> element = (Map<?, ?>) item;
            int stepNo = ((Number) element.get("stepNo")).intValue();
            String kind = element.get("kind") == null ? "" : String.valueOf(element.get("kind"));
            ColumnPayload request = columnPayload(element.get("request"), budget);
            ColumnPayload response = columnPayload(element.get("response"), budget);
            ColumnPayload assertions = columnPayload(element.get("assertions"), budget);
            boolean truncated = request.truncated() || response.truncated() || assertions.truncated();
            long rawBytes = request.rawBytes() + response.rawBytes() + assertions.rawBytes();
            Object tlsRaw = element.get("tlsMode");
            String tlsMode = tlsRaw == null ? null : String.valueOf(tlsRaw);
            jdbcTemplate.update("""
                    INSERT INTO tp_exec_step_snapshot (task_id, step_no, kind, truncated,
                           raw_bytes, request_snapshot, response_snapshot, assertions_snapshot,
                           tls_mode)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, taskId, stepNo, kind, truncated ? 1 : 0, rawBytes,
                    request.gzip(), response.gzip(), assertions.gzip(), tlsMode);
        }
    }

    
    private ColumnPayload columnPayload(Object value, int budget) {
        if (value == null) {
            return new ColumnPayload(null, false, 0);
        }
        String json = serialize(SnapshotJsonSanitizer.sanitizeDeep(value, objectMapper));
        long rawBytes = json.getBytes(StandardCharsets.UTF_8).length;
        boolean truncated = false;
        String stored = json;
        if (rawBytes > budget) {
            String marker = "…[truncated, original " + rawBytes + " bytes]";
            int keep = Math.max(0, budget - marker.getBytes(StandardCharsets.UTF_8).length);
            stored = truncateUtf8(json, keep) + marker;
            truncated = true;
        }
        return new ColumnPayload(gzip(stored), truncated, rawBytes);
    }

    
    private int budgetBytes() {
        String raw = configService.getValue("exec.snapshot.max-bytes",
                String.valueOf(DEFAULT_BUDGET_BYTES));
        try {
            int value = Integer.parseInt(raw.trim());
            return Math.clamp(value, BUDGET_MIN, BUDGET_MAX);
        } catch (NumberFormatException e) {
            return DEFAULT_BUDGET_BYTES;
        }
    }

    
    private static String truncateUtf8(String text, int maxBytes) {
        if (maxBytes <= 0) {
            return "";
        }
        int bytes = 0;
        int i = 0;
        while (i < text.length()) {
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            int cpBytes = utf8Length(codePoint);
            if (bytes + cpBytes > maxBytes) {
                break;
            }
            bytes += cpBytes;
            i += charCount;
        }
        return text.substring(0, i);
    }

    private static int utf8Length(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }

    private String serialize(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new ServiceException("协议快照 JSON 序列化失败: " + e.getMessage()
                    + "——请检查快照内是否存在无法序列化的对象（示例：循环引用节点），改为基础类型后重传",
                    BizErrorCode.VALIDATION_ERROR);
        }
    }

    
    public List<ExecStepSnapshotView> listSnapshots(long taskId) {
        return jdbcTemplate.query("""
                SELECT task_id, step_no, kind, truncated, raw_bytes,
                       request_snapshot, response_snapshot, assertions_snapshot, tls_mode
                FROM tp_exec_step_snapshot
                WHERE task_id = ?
                ORDER BY step_no
                """, (rs, i) -> new ExecStepSnapshotView(
                        rs.getLong("task_id"), rs.getInt("step_no"), rs.getString("kind"),
                        rs.getInt("truncated") == 1, rs.getLong("raw_bytes"),
                        gunzip(rs.getBytes("request_snapshot")),
                        gunzip(rs.getBytes("response_snapshot")),
                        gunzip(rs.getBytes("assertions_snapshot")),
                        rs.getString("tls_mode")),
                taskId);
    }

    
    private static String gunzip(byte[] gzip) {
        if (gzip == null || gzip.length == 0) {
            return null;
        }
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gzip));
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("协议快照 gunzip 失败", e);
        }
    }

    private static byte[] gzip(String text) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
                gz.write(text.getBytes(StandardCharsets.UTF_8));
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("协议快照 gzip 失败", e);
        }
    }

    
    private record ColumnPayload(byte[] gzip, boolean truncated, long rawBytes) {
    }
}
