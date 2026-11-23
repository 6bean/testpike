package com.sixbean.testpike.core.perf.domain.service;

import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.CoreApplication;
import com.sixbean.testpike.core.config.api.dto.ConfigInput;
import com.sixbean.testpike.core.config.domain.service.ConfigService;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Perf CSV dataset ledger contract data pool on S3 with
 * column preview and K8-thresholded row budget).
 * upload parses + validates the CSV (RFC4180 quoting, identifier-safe columns,
 * ragged rows refused by line number), stores the file under the project-routed
 * storage scene perf-dataset and keeps only metadata in tp_perf_dataset; view
 * streams back the first previewRows lines from the object, never from a
 * duplicated DB copy of the payload.
 * <p>Row budget and preview size are hot-updatable K8 keys (perf.dataset.*),
 * never hardcoded: the test flips the budget to 1 through ConfigService and
 * expects the refusal to name the live limit.
 * <p>Real dev DB + S3 (SpringBootTest, no mocks); fixture posture mirrors
 * the sibling PerfScenarioServiceTest (nanoTime-uniq project, cascade cleanup,
 * K8 seed rows restored after mutation).
 */
@SpringBootTest(classes = CoreApplication.class)
@org.springframework.test.context.TestPropertySource(properties = "testpike.scheduling.enabled=false")
class PerfDatasetServiceTest {

    @Autowired
    private PerfDatasetService perfDatasetService;

    @Autowired
    private PerfConfigReader perfConfigReader;

    @Autowired
    private ConfigService configService;

    @Autowired
    private ArtifactStorage artifactStorage;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private long projectId;

    @BeforeEach
    void seedFixtures() {
        cleanupRows();
        long uniq = System.nanoTime() % 1_000_000_000L;
        String projectCode = "PERFDSN" + uniq;
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "压测数据集测试项目_" + uniq, projectCode);
        projectId = jdbcTemplate.queryForObject("SELECT max(id) FROM tp_project", Long.class);
    }

    @AfterEach
    void cleanup() {
        cleanupRows();
        restoreDatasetKeys();
    }

    private void cleanupRows() {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT d.object_key FROM tp_perf_dataset d JOIN tp_project p ON p.id = d.project_id "
                        + "WHERE p.code LIKE 'PERFDSN%'");
        for (Map<String, Object> row : rows) {
            Object key = row.get("object_key");
            if (key != null) {
                artifactStorage.deleteQuietly(String.valueOf(key));
            }
        }
        jdbcTemplate.update("DELETE FROM tp_perf_dataset WHERE project_id IN "
                + "(SELECT id FROM tp_project WHERE code LIKE 'PERFDSN%')");
        jdbcTemplate.update("DELETE FROM tp_project WHERE code LIKE 'PERFDSN%'");
    }

    /** Restore the V273 seed values if a test mutated them (K8 seed rows never left deleted;
        writes go through ConfigService so its cache cannot serve a stale value). */
    private void restoreDatasetKeys() {
        for (Map.Entry<String, String> seed : Map.of(
                "perf.dataset.max-rows", "50000",
                "perf.dataset.preview-rows", "20").entrySet()) {
            String current = jdbcTemplate.query(
                    "SELECT config_value FROM tp_sys_config WHERE config_key = ?",
                    rs -> rs.next() ? rs.getString(1) : null, seed.getKey());
            if (!seed.getValue().equals(current)) {
                Long id = jdbcTemplate.query(
                        "SELECT id FROM tp_sys_config WHERE config_key = ?",
                        rs -> rs.next() ? rs.getLong(1) : null, seed.getKey());
                if (id != null) {
                    configService.delete(1L, id);
                }
                configService.create(1L, new ConfigInput(seed.getKey(), seed.getValue(), "系统",
                        "int", 0, "压测数据集 K8 种子（迁移 V273 随行）"));
            }
        }
    }

    private void setConfig(String key, String value) {
        Long id = jdbcTemplate.query(
                "SELECT id FROM tp_sys_config WHERE config_key = ?",
                rs -> rs.next() ? rs.getLong(1) : null, key);
        if (id != null) {
            configService.delete(1L, id);
        }
        configService.create(1L, new ConfigInput(key, value, "系统", "int", 0,
                "压测数据集 K8 种子（迁移 V273 随行）"));
    }

    private static byte[] csv(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    // ---------- upload: parse + validate + persist metadata ----------

    @Test
    void uploadStoresColumnsAndRowCountAndServesPreviewFromStorage() {
        byte[] content = csv("""
                username,password,orderId
                alice,pw1,ORD-1
                bob,pw2,ORD-2
                carol,pw3,ORD-3
                """);
        long id = perfDatasetService.upload(projectId, "下单账号池", "orders.csv", content);
        Map<String, Object> view = perfDatasetService.view(projectId, id);
        assertThat((String) view.get("columns_json")).contains("username").contains("orderId");
        assertThat(((Number) view.get("row_count")).intValue()).isEqualTo(3);
        @SuppressWarnings("unchecked")
        List<List<String>> preview = (List<List<String>>) view.get("preview_rows");
        assertThat(preview).hasSize(3);
        // U-10: an auth-shaped column name values the cell wholesale at read time
        assertThat(preview.get(0)).containsExactly("alice", "***", "ORD-1");
        // the object itself lives in project-routed storage, not only in the row
        assertThat(artifactStorage.exists(String.valueOf(view.get("object_key")))).isTrue();
    }

    @Test
    void datasetPreviewMasksSensitiveColumnsAndPiiValueShapes() {
        // Worst-case pool: phone/email live in ordinary columns (value-shape facet),
        // the secret lives under a token-named column (column-name facet). The
        // stored object keeps the raw bytes; only the preview read-out is masked.
        byte[] content = csv("""
                user_phone,contact_email,password,orderId
                13812345678,zhangsan@example.com,hunter2,ORD-9
                """);
        long id = perfDatasetService.upload(projectId, "打码样本池", "masked.csv", content);
        Map<String, Object> view = perfDatasetService.view(projectId, id);
        @SuppressWarnings("unchecked")
        List<List<String>> preview = (List<List<String>>) view.get("preview_rows");
        assertThat(preview.get(0)).containsExactly(
                "138****5678", "z***@example.com", "***", "ORD-9");
    }

    @Test
    void uploadAcceptsQuotedCellsAndUtf8Bom() {
        String body = "note,tag\r\n\"a,b\",\"x\"\"y\"\r\nplain,zz\r\n";
        byte[] withBom = new byte[3 + body.getBytes(StandardCharsets.UTF_8).length];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body.getBytes(StandardCharsets.UTF_8), 0, withBom, 3,
                body.getBytes(StandardCharsets.UTF_8).length);
        long id = perfDatasetService.upload(projectId, "引号样本", "quoted.csv", withBom);
        Map<String, Object> view = perfDatasetService.view(projectId, id);
        assertThat(((Number) view.get("row_count")).intValue()).isEqualTo(2);
        @SuppressWarnings("unchecked")
        List<List<String>> preview = (List<List<String>>) view.get("preview_rows");
        assertThat(preview.get(0)).containsExactly("a,b", "x\"y");
    }

    @Test
    void uploadRefusesNonIdentifierColumnWithTheOffendingName() {
        byte[] content = csv("user name,ok\nx,1\n");
        assertThatThrownBy(() -> perfDatasetService.upload(projectId, "坏列名", "bad.csv", content))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("user name");
    }

    @Test
    void uploadRefusesDuplicatedColumnNames() {
        byte[] content = csv("id,id,v\n1,2,3\n");
        assertThatThrownBy(() -> perfDatasetService.upload(projectId, "重复列", "dup.csv", content))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("id");
    }

    @Test
    void uploadRefusesRaggedRowNamingTheLine() {
        byte[] content = csv("a,b\n1,2\n3\n");
        assertThatThrownBy(() -> perfDatasetService.upload(projectId, "参差行", "ragged.csv", content))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("第 2 行");
    }

    @Test
    void uploadRefusesHeaderWithoutAnyDataRow() {
        byte[] content = csv("a,b\n");
        assertThatThrownBy(() -> perfDatasetService.upload(projectId, "空池", "empty.csv", content))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("数据行");
    }

    // ---------- K8 thresholds: hot-updatable, never hardcoded ----------

    @Test
    void uploadRefusesRowsBeyondTheLiveBudgetAndNamesTheLimit() {
        setConfig("perf.dataset.max-rows", "1");
        assertThat(perfConfigReader.datasetMaxRows()).isEqualTo(1);
        byte[] content = csv("a\n1\n2\n");
        assertThatThrownBy(() -> perfDatasetService.upload(projectId, "超量池", "over.csv", content))
                .isInstanceOf(ServiceException.class)
                .hasMessageContaining("上限 1 行");
    }

    @Test
    void previewHonoursTheLivePreviewRowsKey() {
        setConfig("perf.dataset.preview-rows", "2");
        byte[] content = csv("a\n1\n2\n3\n4\n");
        long id = perfDatasetService.upload(projectId, "预览截断", "prev.csv", content);
        Map<String, Object> view = perfDatasetService.view(projectId, id);
        assertThat(((Number) view.get("row_count")).intValue()).isEqualTo(4);
        @SuppressWarnings("unchecked")
        List<List<String>> preview = (List<List<String>>) view.get("preview_rows");
        assertThat(preview).hasSize(2);
    }

    // ---------- ledger window ----------

    @Test
    void listPagedServesBoundedWindowAndRefusesOffListSort() {
        for (int i = 0; i < 3; i++) {
            perfDatasetService.upload(projectId, "分页池" + i, "p" + i + ".csv",
                    csv("a\n1\n2\n"));
        }
        PerfDatasetPageView page = perfDatasetService.listPaged(projectId, 1, 2, null, null);
        assertThat(page.list()).hasSize(2);
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.pageSize()).isEqualTo(2);
        assertThatThrownBy(() -> perfDatasetService.listPaged(projectId, 1, 2, "object_key; DROP", "asc"))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void deleteRemovesRowAndObject() {
        long id = perfDatasetService.upload(projectId, "删除样本", "del.csv", csv("a\n1\n"));
        String objectKey = String.valueOf(perfDatasetService.view(projectId, id).get("object_key"));
        perfDatasetService.delete(projectId, id);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tp_perf_dataset WHERE id = ?", Integer.class, id)).isZero();
        assertThat(artifactStorage.exists(objectKey)).isFalse();
    }

    @Test
    void crossProjectViewIsRefused() {
        long id = perfDatasetService.upload(projectId, "越权样本", "x.csv", csv("a\n1\n"));
        // second project kept in the same PERFDSN code family so @AfterEach cascades it away
        String code = "PERFDSN-X" + (System.nanoTime() % 1_000_000_000L);
        jdbcTemplate.update("INSERT INTO tp_project (name, code, status) VALUES (?, ?, 1)",
                "数据集越权测试项目", code);
        long otherId = jdbcTemplate.queryForObject(
                "SELECT max(id) FROM tp_project WHERE code = ?", Long.class, code);
        assertThatThrownBy(() -> perfDatasetService.view(otherId, id))
                .isInstanceOf(ServiceException.class);
    }
}
