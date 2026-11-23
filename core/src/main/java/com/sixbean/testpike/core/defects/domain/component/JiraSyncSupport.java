package com.sixbean.testpike.core.defects.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.core.config.domain.component.ConfigCipher;
import com.sixbean.testpike.core.executor.api.dto.ExecStepSnapshotView;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.executor.domain.component.ExecStepSnapshotSupport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


@Component
public class JiraSyncSupport {

    private static final Logger log = LoggerFactory.getLogger(JiraSyncSupport.class);

    
    public static final String KEY_ENABLED = "jira.sync.enabled";
    public static final String KEY_BASE_URL = "jira.sync.base-url";
    public static final String KEY_TOKEN = "jira.sync.token";
    public static final String KEY_TIMEOUT_SECONDS = "jira.sync.timeout-seconds";
    private static final String DEFAULT_TIMEOUT_SECONDS = "5";

    
    static final String LABEL_AUTO_DEFECT = "auto-defect";
    
    private static final int ERROR_MAX = 500;
    
    private static final int SYNC_FAILED = 2;
    
    private static final int SYNC_SUCCESS = 1;

    private final JdbcTemplate jdbcTemplate;
    private final ConfigCipher configCipher;
    private final ExecStepSnapshotSupport execStepSnapshotSupport;
    private final ArtifactStorage artifactStorage;
    private final ObjectMapper objectMapper;

    public JiraSyncSupport(JdbcTemplate jdbcTemplate,
                           ConfigCipher configCipher,
                           ExecStepSnapshotSupport execStepSnapshotSupport,
                           ArtifactStorage artifactStorage,
                           ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.configCipher = configCipher;
        this.execStepSnapshotSupport = execStepSnapshotSupport;
        this.artifactStorage = artifactStorage;
        this.objectMapper = objectMapper;
    }

    
    public void syncDefect(long defectId) {
        try {
            doSync(defectId);
        } catch (Exception e) {
            markFailure(defectId, "JIRA 同步异常：" + e.getMessage());
            log.warn("缺陷 {} JIRA 同步失败（不阻断收口）: {}", defectId, e.getMessage(), e);
        }
    }

    private void doSync(long defectId) {
        if (!"1".equals(readConfig(KEY_ENABLED, "0", false))) {
            return;
        }
        String baseUrl = readConfig(KEY_BASE_URL, "", false);
        if (baseUrl.isBlank()) {
            return;
        }
        Map<String, Object> defect = jdbcTemplate.query(
                "SELECT d.project_id, d.title, d.description, p.jira_project_id"
                        + " FROM tp_defect d JOIN tp_project p ON p.id = d.project_id WHERE d.id = ?",
                rs -> rs.next() ? Map.of(
                        "project_id", rs.getLong("project_id"),
                        "title", String.valueOf(rs.getString("title")),
                        "description", String.valueOf(rs.getString("description")),
                        "jira_project_id", String.valueOf(rs.getString("jira_project_id"))) : null,
                defectId);
        if (defect == null) {
            return;
        }
        String jiraProjectId = String.valueOf(defect.get("jira_project_id"));
        if ("null".equals(jiraProjectId) || jiraProjectId.isBlank()) {
            return;
        }
        String token = readConfig(KEY_TOKEN, "", true);
        String key = createIssue(defectId, baseUrl, token, jiraProjectId,
                String.valueOf(defect.get("title")), String.valueOf(defect.get("description")));
        uploadAttachments(baseUrl, token, key, defectId);
        jdbcTemplate.update("UPDATE tp_defect SET external_key = ?, jira_sync_status = ?,"
                + " jira_sync_error = NULL, updated_at = now() WHERE id = ?", key, SYNC_SUCCESS, defectId);
    }

    
    private String createIssue(long defectId, String baseUrl, String token, String jiraProjectKey,
                               String summary, String description) {
        Map<String, Object> project = new LinkedHashMap<>();
        project.put("key", jiraProjectKey);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("project", project);
        fields.put("summary", summary);
        fields.put("description", description);
        fields.put("labels", List.of(LABEL_AUTO_DEFECT));
        Map<String, Object> body = Map.of("fields", fields);
        try {
            Map<?, ?> resp = newRestClient(baseUrl)
                    .post().uri("/rest/api/2/issue")
                    .header("Authorization", "Bearer " + token)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(objectMapper.writeValueAsString(body))
                    .retrieve()
                    .body(Map.class);
            Object key = resp == null ? null : resp.get("key");
            if (key == null || String.valueOf(key).isBlank()) {
                throw new IllegalStateException("JIRA 创建响应缺少 issue key");
            }
            return String.valueOf(key);
        } catch (Exception e) {
            markFailure(defectId, "JIRA 创建失败：" + e.getMessage());
            throw new IllegalStateException("JIRA issue 创建失败", e);
        }
    }

    
    private void uploadAttachments(String baseUrl, String token, String issueKey, long defectId) {
        try {
            List<MultipartPart> parts = collectAttachmentParts(defectId);
            if (parts.isEmpty()) {
                return;
            }
            byte[] boundaryBytes = ("----tpJiraSync" + System.nanoTime()).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] payload = buildMultipartBody(boundaryBytes, parts);
            newRestClient(baseUrl)
                    .post().uri("/rest/api/2/issue/{key}/attachments", issueKey)
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary="
                            + new String(boundaryBytes, java.nio.charset.StandardCharsets.UTF_8))
                    .body(payload)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("缺陷 {} JIRA 附件上传失败（不影响同步成功态）: {}", defectId, e.getMessage());
        }
    }

    
    private List<MultipartPart> collectAttachmentParts(long defectId) {
        List<MultipartPart> parts = new ArrayList<>();
        Long taskId = jdbcTemplate.query(
                "SELECT t.id FROM tp_exec_task t JOIN tp_defect d ON d.case_id = t.case_id"
                        + " WHERE d.id = ? AND t.status IN (4, 5) ORDER BY t.id DESC LIMIT 1",
                rs -> rs.next() ? rs.getLong(1) : null, defectId);
        if (taskId == null) {
            return parts;
        }
        List<ExecStepSnapshotView> snapshots = execStepSnapshotSupport.listSnapshots(taskId);
        if (!snapshots.isEmpty()) {
            List<Map<String, Object>> merged = new ArrayList<>();
            for (ExecStepSnapshotView s : snapshots) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stepNo", s.stepNo());
                item.put("kind", s.kind());
                item.put("truncated", s.truncated());
                item.put("rawBytes", s.rawBytes());
                item.put("request", s.request());
                item.put("response", s.response());
                item.put("assertions", s.assertions());
                merged.add(item);
            }
            try {
                parts.add(new MultipartPart("protocol-snapshot-task" + taskId + ".json",
                        MediaType.APPLICATION_JSON_VALUE,
                        objectMapper.writeValueAsBytes(Map.of("taskId", taskId, "snapshots", merged))));
            } catch (Exception e) {
                log.warn("协议快照合并序列化失败（task={}，跳过该附件）: {}", taskId, e.getMessage());
            }
        }
        List<String> screenshotKeys = parseStringArray(jdbcTemplate.query(
                "SELECT screenshots_json::text FROM tp_exec_task WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null, taskId));
        for (String objectKey : screenshotKeys) {
            byte[] bytes = artifactStorage.readBytesQuietly(objectKey);
            if (bytes == null) {
                continue;
            }
            String name = objectKey.substring(objectKey.lastIndexOf('/') + 1);
            parts.add(new MultipartPart("screenshot-" + name, MediaType.IMAGE_PNG_VALUE, bytes));
        }
        return parts;
    }

    private List<String> parseStringArray(String json) {
        if (json == null || json.isBlank() || "null".equals(json)) {
            return List.of();
        }
        try {
            List<?> list = objectMapper.readValue(json, List.class);
            List<String> keys = new ArrayList<>();
            for (Object item : list) {
                if (item instanceof String s && !s.isBlank()) {
                    keys.add(s);
                }
            }
            return keys;
        } catch (Exception e) {
            return List.of();
        }
    }

    
    private byte[] buildMultipartBody(byte[] boundary, List<MultipartPart> parts) throws java.io.IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (MultipartPart part : parts) {
            out.writeBytes(("--" + new String(boundary, java.nio.charset.StandardCharsets.UTF_8) + "\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeBytes(("Content-Disposition: form-data; name=\"file\"; filename=\""
                    + part.filename() + "\"\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeBytes(("Content-Type: " + part.contentType() + "\r\n\r\n")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.writeBytes(part.content());
            out.writeBytes("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        out.writeBytes(("--" + new String(boundary, java.nio.charset.StandardCharsets.UTF_8) + "--\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    
    private RestClient newRestClient(String baseUrl) {
        int seconds = parseTimeoutSeconds();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(seconds * 1000);
        factory.setReadTimeout(seconds * 1000);
        return RestClient.builder().requestFactory(factory).baseUrl(baseUrl).build();
    }

    private int parseTimeoutSeconds() {
        try {
            return Integer.parseInt(readConfig(KEY_TIMEOUT_SECONDS, DEFAULT_TIMEOUT_SECONDS, false));
        } catch (NumberFormatException e) {
            return Integer.parseInt(DEFAULT_TIMEOUT_SECONDS);
        }
    }

    private void markFailure(long defectId, String reason) {
        try {
            String trimmed = reason == null ? "未知错误" : reason;
            if (trimmed.length() > ERROR_MAX) {
                trimmed = trimmed.substring(0, ERROR_MAX);
            }
            jdbcTemplate.update("UPDATE tp_defect SET jira_sync_status = ?, jira_sync_error = ?,"
                    + " updated_at = now() WHERE id = ? AND jira_sync_status = 0",
                    SYNC_FAILED, trimmed, defectId);
        } catch (Exception e) {
            log.warn("缺陷 {} JIRA 同步失败态回写异常: {}", defectId, e.getMessage());
        }
    }

    
    private String readConfig(String key, String defaultValue, boolean cipher) {
        String[] holder = jdbcTemplate.query(
                "SELECT config_value, encrypt_flag FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? new String[]{rs.getString(1), String.valueOf(rs.getInt(2))} : null, key);
        if (holder == null || holder[0] == null || holder[0].isBlank()) {
            return defaultValue;
        }
        String stored = holder[0].trim();
        if (cipher && "1".equals(holder[1])) {
            try {
                return configCipher.decrypt(stored);
            } catch (Exception e) {
                return stored;
            }
        }
        return stored;
    }
}
