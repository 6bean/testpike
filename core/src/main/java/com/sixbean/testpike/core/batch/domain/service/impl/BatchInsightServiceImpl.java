package com.sixbean.testpike.core.batch.domain.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sixbean.testpike.common.exception.BizErrorCode;
import com.sixbean.testpike.common.exception.ServiceException;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightUpsertInput;
import com.sixbean.testpike.core.batch.api.dto.BatchInsightView;
import com.sixbean.testpike.core.batch.domain.service.BatchInsightService;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Batch failure-insight landing implementation (AO-7 proactive insights, stage 2).
 * <p>Landing semantics: one row per batch, replay overwrites the latest snapshot
 * (ON CONFLICT (batch_id) DO UPDATE — latest-wins, same posture as the per-task
 * classify write-back). project_id is read back from the batch row inside this
 * transaction (server-derived; a payload-supplied project id is never trusted,
 * H1 isolation anchor).
 * <p>Notification fan-out reuses the batch-finished recipient family:
 * tp_project.test_owner_id, falling back to {@link ProjectOwners} when unset
 * (role=2 owners ∪ creator). Dedup key AI_INSIGHT_CLUSTER:{batchId}:{userId}
 * keeps a replayed landing from stacking duplicate station letters. Single
 * recipient failures only warn — the insight ledger row is the durable result,
 * the letter is the pointer.
 * <p>Unattributed honesty: when the clustering says N failures had no AI
 * attribution yet (they were grouped by executor error category instead), the
 * letter content says so plainly instead of pretending the causes are final.
 */
@Service
public class BatchInsightServiceImpl implements BatchInsightService {

    private static final Logger log = LoggerFactory.getLogger(BatchInsightServiceImpl.class);

    private static final String EVENT_TYPE = "AI_INSIGHT_CLUSTER";

    /** Json shape written by {@link #writeClustersJson} — read back symmetrically. */
    private static final TypeReference<List<Map<String, Object>>> CLUSTERS_TYPE =
            new TypeReference<>() {
            };

    private final JdbcTemplate jdbcTemplate;
    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final ObjectMapper objectMapper;

    public BatchInsightServiceImpl(JdbcTemplate jdbcTemplate,
                                   NotificationService notificationService,
                                   ProjectOwners projectOwners,
                                   ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void upsert(long batchId, BatchInsightUpsertInput input) {
        Map<String, Object> batch;
        try {
            batch = jdbcTemplate.queryForMap(
                    "SELECT b.project_id, b.name, p.name AS project_name, p.test_owner_id"
                            + " FROM tp_exec_batch b JOIN tp_project p ON p.id = b.project_id"
                            + " WHERE b.id = ?", batchId);
        } catch (EmptyResultDataAccessException e) {
            throw new ServiceException("批次不存在，失败洞察结果无法保存：batchId=" + batchId,
                    BizErrorCode.NOT_FOUND);
        }
        long projectId = ((Number) batch.get("project_id")).longValue();
        int failCount = input.failCount() == null ? 0 : Math.max(0, input.failCount());
        int unattributed = input.unattributedCount() == null ? 0
                : Math.max(0, input.unattributedCount());
        String clustersJson = writeClustersJson(input.clusters());

        jdbcTemplate.update("""
                INSERT INTO tp_batch_insight (batch_id, project_id, fail_count,
                        unattributed_count, clusters_json, summary, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (batch_id) DO UPDATE SET
                    project_id = EXCLUDED.project_id,
                    fail_count = EXCLUDED.fail_count,
                    unattributed_count = EXCLUDED.unattributed_count,
                    clusters_json = EXCLUDED.clusters_json,
                    summary = EXCLUDED.summary,
                    updated_at = now()
                """, batchId, projectId, failCount, unattributed, clustersJson,
                trimSummary(input.summary()));

        notifyRecipients(batchId, projectId, batch, failCount, unattributed, input.summary());
    }

    @Override
    public BatchInsightView findByBatch(long batchId) {
        // Honest-absence read door: no row (or unknown batch) = null, never 404 —
        // the report page hides the card for the normal all-pass / gate-off case.
        // clusters_json parse failures degrade to an empty group list (the counts
        // and summary on the row stay truthful) instead of failing the whole read.
        List<BatchInsightView> rows = jdbcTemplate.query(
                "SELECT batch_id, fail_count, unattributed_count, clusters_json, summary "
                        + "FROM tp_batch_insight WHERE batch_id = ?",
                (rs, rowNum) -> new BatchInsightView(rs.getLong("batch_id"),
                        rs.getInt("fail_count"), rs.getInt("unattributed_count"),
                        parseClusters(rs.getString("clusters_json")), rs.getString("summary")),
                batchId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private List<Map<String, Object>> parseClusters(String clustersJson) {
        if (clustersJson == null || clustersJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(clustersJson, CLUSTERS_TYPE);
        } catch (Exception e) {
            log.warn("洞察分组 JSON 解析失败（卡面分组诚实降级为空，计数与摘要仍如实返回）: {}",
                    e.getMessage());
            return List.of();
        }
    }

    private String writeClustersJson(List<Map<String, Object>> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(clusters);
        } catch (Exception e) {
            log.warn("洞察分组 JSON 序列化失败（batch 卡面分组缺失，账行仍落）: {}", e.getMessage());
            return "[]";
        }
    }

    private static String trimSummary(String summary) {
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return summary.length() > 500 ? summary.substring(0, 500) : summary;
    }

    private void notifyRecipients(long batchId, long projectId, Map<String, Object> batch,
                                  int failCount, int unattributed, String summary) {
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        Object testOwnerId = batch.get("test_owner_id");
        if (testOwnerId != null) {
            recipients.add(((Number) testOwnerId).longValue());
        } else {
            recipients.addAll(projectOwners.resolve(projectId));
        }
        if (recipients.isEmpty()) {
            log.info("批次 {} 洞察通知无收件人（test_owner 未设且无项目负责人），账行已落", batchId);
            return;
        }
        String batchLabel = batch.get("name") == null ? "#" + batchId : String.valueOf(batch.get("name"));
        String projectLabel = batch.get("project_name") == null
                ? "项目" + projectId : "项目「" + batch.get("project_name") + "」";
        String title = "失败根因洞察：批次「" + batchLabel + "」（" + failCount + " 条失败）";
        StringBuilder content = new StringBuilder(projectLabel)
                .append("批次「").append(batchLabel).append("」执行完毕，失败原因聚类：");
        content.append(summary == null || summary.isBlank()
                ? "共 " + failCount + " 条失败" : summary);
        if (unattributed > 0) {
            content.append("；其中 ").append(unattributed).append(" 条尚未完成智能归因，")
                    .append("暂按执行器错误分类统计，归因补齐后洞察卡会自动刷新");
        }
        content.append("。可前往批次详情查看洞察卡，或点击进入对话继续追问。");
        for (Long userId : recipients) {
            try {
                notificationService.create(new NotificationCreateInput(userId, projectId,
                        EVENT_TYPE, title, content.toString(),
                        EVENT_TYPE + ":" + batchId + ":" + userId));
            } catch (Exception e) {
                log.warn("AI_INSIGHT_CLUSTER 通知失败（batchId={} user={}）: {}",
                        batchId, userId, e.getMessage());
            }
        }
    }
}
