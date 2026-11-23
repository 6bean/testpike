package com.sixbean.testpike.core.tool.domain.service.impl;

import com.sixbean.testpike.common.approval.ApprovalStateMachine;
import com.sixbean.testpike.common.approval.ApprovalStatus;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.common.storage.StorageGrant;
import com.sixbean.testpike.common.storage.StorageKeys;
import com.sixbean.testpike.core.common.clientobject.PageResult;
import com.sixbean.testpike.core.common.domain.component.PageQueryPolicy;
import com.sixbean.testpike.core.common.domain.component.SortColumnPolicy;
import com.sixbean.testpike.core.executor.domain.component.StorageRouter;
import com.sixbean.testpike.core.tool.api.dto.ToolCreateInput;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketDownloadView;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketItem;
import com.sixbean.testpike.core.tool.api.dto.ToolMarketQuery;
import com.sixbean.testpike.core.tool.domain.component.ToolPackSupport;
import com.sixbean.testpike.core.tool.domain.component.ToolType;
import com.sixbean.testpike.core.tool.domain.service.ToolMarketService;
import com.sixbean.testpike.core.tool.domain.service.ToolService;
import com.sixbean.testpike.core.tool.infrastructure.dataobject.ToolMarketRow;
import org.springframework.jdbc.core.BeanPropertyRowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;


@Service
public class ToolMarketServiceImpl implements ToolMarketService {

    
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "t.id",
            "name", "t.name",
            "type", "t.type",
            "category", "t.category",
            "currentVersion", "t.current_version",
            "createdAt", "t.created_at",
            "downloadCount", "download_count",
            "refProjectCount", "ref_project_count",
            "call30d", "call30d_count");
    
    private static final int MAX_OFFSET = 100_000;
    
    private static final String CALL_WINDOW = "interval '30 days'";
    
    private static final String FORMAT_MD = "md";
    private static final String FORMAT_ZIP = "zip";
    
    private static final String SCENE = "tool-pack";

    private final NamedParameterJdbcTemplate named;
    private final ToolService toolService;
    private final ToolPackSupport toolPackSupport;
    private final StorageRouter storageRouter;

    public ToolMarketServiceImpl(NamedParameterJdbcTemplate named, ToolService toolService,
                                 ToolPackSupport toolPackSupport, StorageRouter storageRouter) {
        this.named = named;
        this.toolService = toolService;
        this.toolPackSupport = toolPackSupport;
        this.storageRouter = storageRouter;
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    public PageResult<ToolMarketItem> marketList(ToolMarketQuery query) {
        int safePage = Math.max(query.page(), 1);
        int safeSize = PageQueryPolicy.enforceSize(query.size());
        long offset = PageQueryPolicy.clampOffset(safePage, safeSize, MAX_OFFSET);
        String sortColumn = SortColumnPolicy.require(query.sortBy(), SORT_COLUMNS, "工具市场");
        if (sortColumn == null) {
            sortColumn = "t.created_at";
        }
        String sortDir = "asc".equalsIgnoreCase(query.sortDir()) ? "ASC" : "DESC";

        MapSqlParameterSource params = new MapSqlParameterSource();
        StringBuilder where = new StringBuilder(" WHERE t.status = :published");
        params.addValue("published", ApprovalStatus.PUBLISHED.code());
        if (query.keyword() != null && !query.keyword().isBlank()) {
            where.append(" AND (t.name ILIKE :keyword OR COALESCE(t.description, '') ILIKE :keyword")
                    .append(" OR COALESCE(t.body, '') ILIKE :keyword)");
            params.addValue("keyword", "%" + query.keyword().trim() + "%");
        }
        if (query.type() != null) {
            where.append(" AND t.type = :type");
            params.addValue("type", query.type());
        }
        if (query.category() != null && !query.category().isBlank()) {
            where.append(" AND t.category = :category");
            params.addValue("category", query.category().trim());
        }
        Long total = named.queryForObject("SELECT count(*) FROM tp_tool t" + where, params, Long.class);
        params.addValue("size", safeSize).addValue("offset", offset);
        List<ToolMarketItem> list = named.query(SELECT_MARKET + where
                        + " ORDER BY " + sortColumn + " " + sortDir + ", t.id DESC LIMIT :size OFFSET :offset",
                        params, new BeanPropertyRowMapper<>(ToolMarketRow.class))
                .stream().map(ToolMarketRow::toInfo).toList();
        return new PageResult<>(list, total != null ? total : 0L);
    }

    
    private static final String SELECT_MARKET = """
            SELECT t.id, t.project_id, t.name, t.type, t.category, t.status, t.current_version,
                   t.body, t.tags::text AS tags_json,
                   t.download_count,
                   (SELECT count(*) FROM tp_project_tool pt
                     WHERE pt.tool_id = t.id)::int AS ref_project_count,
                   (SELECT count(*) FROM tp_tool_invocation i
                     WHERE i.tool_id = t.id AND i.created_at >= now() - %s)::int AS call30d_count,
                   au.display_name AS author_name,
                   t.created_at
            FROM tp_tool t
            LEFT JOIN tp_user au ON au.id = t.created_by
            """.formatted(CALL_WINDOW);

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public ToolMarketDownloadView download(Long toolId, String format) {
        ToolInfo tool = toolService.get(toolId);
        if (!ApprovalStateMachine.isPublished(tool.status())) {
            throw new ServiceException("仅已发布的工具可下载（当前状态：" + tool.statusLabel()
                    + "）；未发布内容请通过详情查看", BizErrorCode.VALIDATION_ERROR);
        }
        String fmt = format == null ? "" : format.trim().toLowerCase();
        if (!FORMAT_MD.equals(fmt) && !FORMAT_ZIP.equals(fmt)) {
            throw new ServiceException("下载格式「" + format + "」不支持（可选：md=文档正文 / zip=契约包）",
                    BizErrorCode.VALIDATION_ERROR);
        }
        Map<String, Object> row = contractRow(toolId);
        boolean doc = tool.type() == ToolType.DOC.code();
        String body = row.get("body") == null ? null : row.get("body").toString();
        if (FORMAT_MD.equals(fmt) && !doc) {
            throw new ServiceException("可执行工具无 Markdown 正文，请改用 zip 契约包下载"
                    + "（可选：md=文档正文 / zip=契约包）", BizErrorCode.VALIDATION_ERROR);
        }
        String baseName = tool.name() + "." + fmt;
        byte[] content;
        String contentType;
        if (FORMAT_ZIP.equals(fmt)) {
            content = toolPackSupport.buildPack(buildContract(row), doc ? body : null);
            contentType = "application/zip";
        } else {
            content = body.getBytes(StandardCharsets.UTF_8);
            contentType = "text/markdown";
        }

        String objectKey = StorageKeys.build(storageRouter.keyPrefix(),
                tool.projectId() == null ? 0L : tool.projectId(), SCENE, baseName);
        storageRouter.systemProvider().put(objectKey, content, contentType);
        StorageGrant grant = storageRouter.systemProvider().presignGet(objectKey, storageRouter.urlTtl());
        named.update("UPDATE tp_tool SET download_count = download_count + 1 WHERE id = :id",
                new MapSqlParameterSource("id", toolId));
        return new ToolMarketDownloadView(toolId, fmt, baseName, grant.key(), grant.url());
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public ToolInfo importTool(Long operatorId, Long projectId, String filename, byte[] content) {
        if (content == null || content.length == 0) {
            throw new ServiceException("导入文件内容为空", BizErrorCode.VALIDATION_ERROR);
        }
        ToolCreateInput input = toolPackSupport.parseToCreateInput(projectId, filename, content);
        return toolService.create(operatorId, input);
    }

    // ------------------------------------------------------------------
    // ------------------------------------------------------------------

    
    private Map<String, Object> contractRow(Long toolId) {
        return named.queryForMap("SELECT name, type, category, description, body,"
                        + " tags::text AS tags_json, input_schema::text AS input_schema_json,"
                        + " output_schema::text AS output_schema_json, exec_kind,"
                        + " exec_spec::text AS exec_spec_json, timeout_ms"
                        + " FROM tp_tool WHERE id = :id",
                new MapSqlParameterSource("id", toolId));
    }

    
    private Map<String, Object> buildContract(Map<String, Object> row) {
        return toolPackSupport.contractFields(row.get("name"), row.get("type"),
                row.get("category"), row.get("description"), row.get("body"),
                row.get("tags_json"), row.get("input_schema_json"), row.get("output_schema_json"),
                row.get("exec_kind"), row.get("exec_spec_json"), row.get("timeout_ms"));
    }
}
