package com.sixbean.testpike.core.perf.domain.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.common.util.DesensitizeUtils;
import com.sixbean.testpike.core.audit.domain.component.Audit;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import com.sixbean.testpike.core.perf.api.dto.PerfDatasetPageView;
import com.sixbean.testpike.core.perf.domain.component.PerfConfigReader;
import com.sixbean.testpike.core.perf.domain.component.PerfCsvData;
import com.sixbean.testpike.core.perf.domain.component.PerfCsvParser;
import com.sixbean.testpike.core.perf.domain.component.PerfDatasetAllocPolicy;
import com.sixbean.testpike.core.perf.domain.component.PerfRowAllocModes;
import com.sixbean.testpike.core.perf.domain.service.PerfDatasetService;
import com.sixbean.testpike.core.mock.domain.component.PageWindow;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Perf CSV dataset ledger implementation (see {@link PerfDatasetService}).
 * <p>Upload path: parse+validate the CSV with the live K8 row budget → write
 * the bytes into project-routed object storage under the perf-dataset scene →
 * insert metadata only. Any storage failure aborts before a row exists, so the
 * ledger can never reference a missing object; delete removes the row first
 * and the object quietly afterwards (an orphan object is harmless, a dangling
 * row is not).
 * <p>Preview path: bytes are read back from the same project route (read/write
 * share {@link ArtifactStorage} key parsing) and re-parsed with the budget
 * switched off, so preview of a previously accepted file cannot fail on size.
 */
@Service
public class PerfDatasetServiceImpl implements PerfDatasetService {

    private static final Logger log = LoggerFactory.getLogger(PerfDatasetServiceImpl.class);

    /** Object-storage scene for perf CSV pools (StorageKeys enum, PRD 9.3.11(4) extension). */
    private static final String SCENE = "perf-dataset";

    private static final String SELECT_COLUMNS = """
            id, project_id, name, file_name, object_key,
            columns_json::text AS columns_json, row_count, size_bytes,
            created_at, updated_at
            """;

    /** Ledger sort whitelist: only physical columns, JSON payload stays off-list. */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "name", "name",
            "row_count", "row_count",
            "size_bytes", "size_bytes",
            "created_at", "created_at",
            "updated_at", "updated_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final PerfCsvParser csvParser;
    private final PerfConfigReader configReader;
    private final ArtifactStorage storage;
    /** Program-lens scope single source for both paged perf doors . */
    private final com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport lensScopeSupport;

    public PerfDatasetServiceImpl(NamedParameterJdbcTemplate jdbc, ObjectMapper objectMapper,
                                  PerfCsvParser csvParser, PerfConfigReader configReader,
                                  ArtifactStorage storage,
                                  com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport lensScopeSupport) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.csvParser = csvParser;
        this.configReader = configReader;
        this.storage = storage;
        this.lensScopeSupport = lensScopeSupport;
    }

    @Override
    @Audit(objectType = "perf_dataset", action = "upload", objectId = "#result")
    public long upload(long projectId, String name, String fileName, byte[] content) {
        if (name == null || name.isBlank()) {
            throw new ServiceException("请填写数据集名称；请在「压测工作台」场景向导「数据池（CSV 数据集）」的名称项填写（示例：登录用户池）", BizErrorCode.VALIDATION_ERROR);
        }
        if (fileName == null || fileName.isBlank()) {
            throw new ServiceException("请选择要上传的 CSV 文件；请在「压测工作台」场景向导「数据池（CSV 数据集）」→「上传数据集 CSV」先选择本地 .csv 文件再上传", BizErrorCode.VALIDATION_ERROR);
        }
        PerfCsvData data = csvParser.parse(content, configReader.datasetMaxRows());
        String objectKey = storage.buildProjectObjectKey(projectId, SCENE, fileName);
        storage.putBytesForProject(projectId, objectKey, content, "text/csv;charset=UTF-8");
        Long id = jdbc.queryForObject("""
                        INSERT INTO tp_perf_dataset (project_id, name, file_name, object_key,
                            columns_json, row_count, size_bytes)
                        VALUES (:p, :n, :f, :k, CAST(:c AS jsonb), :r, :s)
                        RETURNING id""",
                new MapSqlParameterSource("p", projectId).addValue("n", name.trim())
                        .addValue("f", fileName).addValue("k", objectKey)
                        .addValue("c", writeJson(data.headers()))
                        .addValue("r", data.rowCount()).addValue("s", content.length),
                Long.class);
        if (id == null) {
            throw new ServiceException("数据集登记失败；请在「压测工作台」场景向导「数据池（CSV 数据集）」重新上传一次，持续失败时把本提示转平台管理员核查对象存储与数据库写入", BizErrorCode.INTERNAL_ERROR);
        }
        return id;
    }

    @Override
    public Map<String, Object> view(long projectId, long id) {
        Map<String, Object> row = requireRow(projectId, id);
        row.put("preview_rows", readPreviewRows(String.valueOf(row.get("object_key"))));
        return row;
    }

    @Override
    public List<Map<String, Object>> list(long projectId) {
        return jdbc.queryForList("SELECT " + SELECT_COLUMNS + " FROM tp_perf_dataset "
                + "WHERE project_id = :p ORDER BY id DESC", new MapSqlParameterSource("p", projectId));
    }

    @Override
    public PerfDatasetPageView listPaged(long projectId, int page, int pageSize,
                                         String sortBy, String sortDir) {
        return listPaged(0L, 0L, projectId, page, pageSize, sortBy, sortDir);
    }

    @Override
    public PerfDatasetPageView listPaged(long operatorId, long programId, long projectId,
                                         int page, int pageSize, String sortBy, String sortDir) {
        // Anchor rule + lens resolution + predicate all ride the perf shared source.
        lensScopeSupport.requireAnchor(projectId, programId, "压测数据集");
        Set<Long> lensProjectIds = lensScopeSupport.resolveLens(operatorId, programId);
        PageWindow window = PageWindow.of(page, pageSize);
        MapSqlParameterSource params = new MapSqlParameterSource();
        String scope = com.sixbean.testpike.core.perf.domain.component.PerfLensScopeSupport
                .projectScope(lensProjectIds, projectId, params);
        Long total = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tp_perf_dataset WHERE " + scope, params, Long.class);
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                        + " FROM tp_perf_dataset WHERE " + scope
                        + orderClause(sortBy, sortDir) + " LIMIT :limit OFFSET :offset",
                params.addValue("limit", window.size()).addValue("offset", window.offset()));
        return new PerfDatasetPageView(rows, total == null ? 0L : total, window.page(), window.size());
    }

    @Override
    @Audit(objectType = "perf_dataset", action = "delete", objectId = "#id")
    public void delete(long projectId, long id) {
        Map<String, Object> row = requireRow(projectId, id);
        // A scenario may still reference the pool (FK); refuse naming the
        // blocker instead of letting the FK answer as a raw 500.
        Long refs = jdbc.queryForObject("SELECT count(*) FROM tp_perf_scenario WHERE dataset_id = :id",
                new MapSqlParameterSource("id", id), Long.class);
        if (refs != null && refs > 0) {
            throw new ServiceException("该数据集仍被 " + refs + " 个压测场景引用，请先解除场景绑定再删除（入口：「压测工作台」场景编辑的「数据池（CSV 数据集）」绑定项，逐个改绑或改选「未绑定」后重试）",
                    BizErrorCode.RESOURCE_CONFLICT);
        }
        int deleted = jdbc.update("DELETE FROM tp_perf_dataset WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId));
        if (deleted == 0) {
            throw new ServiceException("压测数据集不存在或不属于该项目；请刷新「压测工作台」场景向导的「数据池（CSV 数据集）」列表后重新选择，列表没有该行=已被删除", BizErrorCode.NOT_FOUND);
        }
        storage.deleteQuietly(String.valueOf(row.get("object_key")));
    }

    @Override
    public Map<String, Object> allocPolicy() {
        String engine = configReader.defaultEngine();
        Map<String, Object> policy = new java.util.LinkedHashMap<>();
        policy.put("engine", engine);
        policy.put("defaultMode", configReader.datasetRowAllocMode());
        policy.put("supportedModes", PerfDatasetAllocPolicy.supportedModes(engine));
        policy.put("allModes", PerfRowAllocModes.VALUES.stream().sorted().toList());
        return policy;
    }

    /** Row lookup by project (cross-project ids answer the same 404 wording as the sibling doors). */
    private Map<String, Object> requireRow(long projectId, long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT " + SELECT_COLUMNS
                + " FROM tp_perf_dataset WHERE id = :id AND project_id = :p",
                new MapSqlParameterSource("id", id).addValue("p", projectId));
        if (rows.isEmpty()) {
            throw new ServiceException("压测数据集不存在或不属于该项目；请刷新「压测工作台」场景向导的「数据池（CSV 数据集）」列表后重新选择，列表没有该行=已被删除", BizErrorCode.NOT_FOUND);
        }
        return rows.get(0);
    }

    /**
     * Stream the first previewRows() lines back from the stored object. A
     * vanished object or an unreadable file is reported honestly rather than
     * silently answered with an empty preview.
     * <p>U-10: the read-out is masked cell by cell through the shared
     * two-facet rule (auth-shaped column names star-valued, phone/email shapes
     * masked in every other column). The stored object keeps the raw bytes —
     * only the preview face is hidden, so executors still read real values.
     */
    private List<List<String>> readPreviewRows(String objectKey) {
        byte[] content = storage.readBytesQuietly(objectKey);
        if (content == null) {
            throw new ServiceException("数据集文件在对象存储中缺失，请在「压测工作台」场景向导「数据池（CSV 数据集）」→「上传数据集 CSV」重新上传同名文件即可恢复预览", BizErrorCode.NOT_FOUND);
        }
        // Budget switched off on read-back: the file already passed upload validation.
        PerfCsvData data = csvParser.parse(content, Integer.MAX_VALUE);
        int limit = configReader.datasetPreviewRows();
        List<List<String>> masked = new ArrayList<>();
        for (List<String> row : data.rows().subList(0, Math.min(limit, data.rowCount()))) {
            List<String> cells = new ArrayList<>(row.size());
            for (int c = 0; c < row.size(); c++) {
                cells.add(DesensitizeUtils.maskSensitiveCell(data.headers().get(c), row.get(c)));
            }
            masked.add(cells);
        }
        return masked;
    }

    private static String orderClause(String sortBy, String sortDir) {
        String column = SortColumnPolicy.require(sortBy, SORT_COLUMNS, "压测数据集列表");
        if (column == null) {
            return " ORDER BY id DESC";
        }
        return " ORDER BY " + column
                + ("asc".equalsIgnoreCase(sortDir) ? " ASC" : " DESC")
                + ", id DESC";
    }

    private String writeJson(List<String> values) {
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException e) {
            log.warn("数据集列名序列化失败（不应发生，列名已是安全标识符）", e);
            throw new ServiceException("数据集列名保存失败；请在「压测工作台」场景向导「数据池（CSV 数据集）」重新上传，仍失败时把本提示转平台管理员排查存储", BizErrorCode.INTERNAL_ERROR);
        }
    }
}
