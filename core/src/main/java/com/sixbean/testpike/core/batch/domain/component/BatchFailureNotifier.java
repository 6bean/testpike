package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.FeishuWebhookChannel;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;


@Component
public class BatchFailureNotifier {

    private static final Logger log = LoggerFactory.getLogger(BatchFailureNotifier.class);

    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AfterCommitExecutor afterCommitExecutor;
    private final FeishuWebhookChannel feishuWebhookChannel;
    private final BatchNotifySwitch batchNotifySwitch;
    private final JdbcTemplate jdbcTemplate;

    public BatchFailureNotifier(NotificationService notificationService,
                                ProjectOwners projectOwners,
                                AfterCommitExecutor afterCommitExecutor,
                                FeishuWebhookChannel feishuWebhookChannel,
                                BatchNotifySwitch batchNotifySwitch,
                                JdbcTemplate jdbcTemplate) {
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.afterCommitExecutor = afterCommitExecutor;
        this.feishuWebhookChannel = feishuWebhookChannel;
        this.batchNotifySwitch = batchNotifySwitch;
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public void notifyPartialFailed(long batchId) {
        Map<String, Object> batch = jdbcTemplate.queryForMap(
                "SELECT b.project_id, b.name, b.notify_feishu, p.name AS project_name, p.test_owner_id,"
                        + " p.notify_feishu AS project_notify_feishu"
                        + " FROM tp_exec_batch b JOIN tp_project p ON p.id = b.project_id WHERE b.id = ?",
                batchId);

        long projectId = ((Number) batch.get("project_id")).longValue();
        List<String> failedCases = jdbcTemplate.queryForList(
                "SELECT tc.title FROM tp_batch_case bc JOIN tp_test_case tc ON tc.id = bc.case_id"
                        + " WHERE bc.batch_id = ? AND bc.result = 2 ORDER BY bc.case_id",
                String.class, batchId);

        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        Long testOwnerId = batch.get("test_owner_id") == null
                ? null : ((Number) batch.get("test_owner_id")).longValue();
        if (testOwnerId != null) {
            recipients.add(testOwnerId);
        } else {
            recipients.addAll(projectOwners.resolve(projectId));
        }
        if (recipients.isEmpty()) {
            log.warn("批次 {} 失败通知无收件人（test_owner 未设且无项目负责人）", batchId);
            return;
        }

        String batchLabel = batch.get("name") == null ? "#" + batchId : String.valueOf(batch.get("name"));
        String projectLabel = batch.get("project_name") == null
                ? "项目" + projectId : "项目「" + batch.get("project_name") + "」";
        String title = "批次执行失败：" + batchLabel + "（" + failedCases.size() + " 条失败）";
        StringBuilder content = new StringBuilder(projectLabel)
                .append("批次「").append(batchLabel).append("」执行完成，")
                .append("失败 ").append(failedCases.size()).append(" 条");
        if (!failedCases.isEmpty()) {
            content.append("（用例：").append(String.join("、", failedCases)).append("）");
        }
        content.append("。请前往批次详情查看失败原因，或发起失败用例重跑。");

        afterCommitExecutor.execute(() -> {
            for (Long userId : recipients) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, projectId,
                            "BATCH_FAILED", title, content.toString(),
                            "BATCH_FAILED:" + batchId + ":" + userId));
                } catch (Exception e) {
                    log.warn("BATCH_FAILED 通知失败（batchId={} user={}）: {}",
                            batchId, userId, e.getMessage());
                }
            }
            if (batchNotifySwitch.feishuEnabled(batchId)) {
                try {
                    feishuWebhookChannel.sendBatchNotice(batchId);
                } catch (Exception e) {
                    log.warn("BATCH_FAILED 飞书群通知异常已兜底（batchId={}）: {}", batchId, e.getMessage());
                }
            }
        });
    }
}
