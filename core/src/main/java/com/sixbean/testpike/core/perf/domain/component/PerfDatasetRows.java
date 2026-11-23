package com.sixbean.testpike.core.perf.domain.component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.executor.domain.component.ArtifactStorage;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads one bound perf CSV dataset into memory at engine-compile time so the
 * generated scripts can carry its rows .
 * <p>Supply channel ruling (recon card ): rows are materialized as a
 * sidecar file next to the compiled artifact inside the shared artifact root —
 * the dispatch envelope stays untouched (zero new channel; the same-host
 * assumption rides the existing artifact-path contract and migrates with the
 * A-10 artifact S3-ization). Read-back goes through the same
 * {@link ArtifactStorage} route the upload wrote to, and re-parses with the
 * {@link PerfCsvParser} single dialect (budget off: the file already passed
 * upload validation), so what was accepted once can never fail to load.
 * <p>A vanished object is an honest failure naming the dataset id (a silently
 * row-less run under a bound pool would fake the declared data shape).
 */
@Component
public class PerfDatasetRows {

    private final NamedParameterJdbcTemplate jdbc;
    private final ArtifactStorage storage;
    private final PerfCsvParser csvParser;
    private final ObjectMapper objectMapper;

    public PerfDatasetRows(NamedParameterJdbcTemplate jdbc, ArtifactStorage storage,
                           PerfCsvParser csvParser, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.storage = storage;
        this.csvParser = csvParser;
        this.objectMapper = objectMapper;
    }

    /**
     * Load the ledger row for a bound dataset and parse its stored file.
     *
     * @param projectId owning project (cross-project ids answer the same
     *                  not-found wording as the dataset doors — no leak)
     * @param datasetId tp_perf_dataset.id pinned on the scenario
     */
    public Dataset load(long projectId, long datasetId) {
        String objectKey = jdbc.queryForList("SELECT object_key FROM tp_perf_dataset "
                        + "WHERE id = :d AND project_id = :p",
                new MapSqlParameterSource("d", datasetId).addValue("p", projectId))
                .stream().findFirst().map(r -> String.valueOf(r.get("object_key")))
                .orElseThrow(() -> new ServiceException(
                        "压测数据集不存在或不属于该项目（id=" + datasetId + "），无法编译数据池脚本",
                        BizErrorCode.NOT_FOUND));
        byte[] content = storage.readBytesQuietly(objectKey);
        if (content == null) {
            throw new ServiceException("压测数据集文件在对象存储中缺失（id=" + datasetId
                    + "），请重新上传数据集后再发起运行", BizErrorCode.NOT_FOUND);
        }
        PerfCsvData parsed = csvParser.parse(content, Integer.MAX_VALUE);
        return new Dataset(datasetId, parsed.headers(), parsed.rows());
    }

    /**
     * Parsed pool payload plus the two sidecar serializations the engines need.
     * Re-serialization (instead of shipping the raw upload bytes) normalizes
     * BOM/line-endings/dialect so the engine-side readers see exactly the
     * validated columns and rows.
     */
    public record Dataset(long id, List<String> columns, List<List<String>> rows) {

        public int rowCount() {
            return rows.size();
        }

        /** JSON array of column→value objects for the k6 init context (open()). */
        public String toJsonRows(ObjectMapper mapper) {
            List<Map<String, String>> objects = new ArrayList<>(rows.size());
            for (List<String> row : rows) {
                Map<String, String> byColumn = new LinkedHashMap<>();
                for (int c = 0; c < columns.size(); c++) {
                    byColumn.put(columns.get(c), row.get(c));
                }
                objects.add(byColumn);
            }
            try {
                return mapper.writeValueAsString(objects);
            } catch (Exception impossible) {
                throw new ServiceException("压测数据集行序列化失败", BizErrorCode.INTERNAL_ERROR);
            }
        }

        /** RFC4180 text for the Gatling csv() feeder (quote cells riding specials). */
        public String toCsvText() {
            StringBuilder out = new StringBuilder();
            for (String column : columns) {
                out.append(escape(column)).append(',');
            }
            out.setLength(out.length() - 1);
            out.append('\n');
            for (List<String> row : rows) {
                for (String cell : row) {
                    out.append(escape(cell)).append(',');
                }
                out.setLength(out.length() - 1);
                out.append('\n');
            }
            return out.toString();
        }

        private static String escape(String cell) {
            if (cell.indexOf(',') < 0 && cell.indexOf('"') < 0
                    && cell.indexOf('\n') < 0 && cell.indexOf('\r') < 0) {
                return cell;
            }
            return '"' + cell.replace("\"", "\"\"") + '"';
        }
    }
}
