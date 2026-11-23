package com.sixbean.testpike.core.batch.domain.component;

import com.sixbean.testpike.common.report.BadgeRatePhrase;
import com.sixbean.testpike.core.common.domain.component.AfterCommitExecutor;
import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.api.dto.NotificationCreateInput;
import com.sixbean.testpike.core.notification.domain.component.FeishuWebhookChannel;
import com.sixbean.testpike.core.notification.domain.service.NotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;


@Component
public class BatchFinishedNotifier {

    private static final Logger log = LoggerFactory.getLogger(BatchFinishedNotifier.class);

    private static final int BATCH_DONE = 6;

    private final NotificationService notificationService;
    private final ProjectOwners projectOwners;
    private final AfterCommitExecutor afterCommitExecutor;
    private final BatchNotifySwitch batchNotifySwitch;
    private final FeishuWebhookChannel feishuWebhookChannel;
    private final JdbcTemplate jdbcTemplate;

    public BatchFinishedNotifier(NotificationService notificationService,
                                 ProjectOwners projectOwners,
                                 AfterCommitExecutor afterCommitExecutor,
                                 BatchNotifySwitch batchNotifySwitch,
                                 FeishuWebhookChannel feishuWebhookChannel,
                                 JdbcTemplate jdbcTemplate) {
        this.notificationService = notificationService;
        this.projectOwners = projectOwners;
        this.afterCommitExecutor = afterCommitExecutor;
        this.batchNotifySwitch = batchNotifySwitch;
        this.feishuWebhookChannel = feishuWebhookChannel;
        this.jdbcTemplate = jdbcTemplate;
    }

    
    public void notifyFinished(long batchId, int targetStatus) {
        Map<String, Object> view;
        try {
            view = jdbcTemplate.queryForMap(
                    "SELECT r.project_id, r.total, r.pass_cnt, r.fail_cnt, r.skip_cnt,"
                            + " r.timeout_cnt, r.pass_rate, b.name,"
                            + " p.name AS project_name, p.test_owner_id"
                            + " FROM tp_batch_report r"
                            + " JOIN tp_exec_batch b ON b.id = r.batch_id"
                            + " JOIN tp_project p ON p.id = b.project_id"
                            + " WHERE r.batch_id = ?", batchId);
        } catch (EmptyResultDataAccessException e) {
            log.warn("批次 {} 完成通知跳过（报告快照行缺失，收口写入异常）", batchId);
            return;
        }

        long projectId = ((Number) view.get("project_id")).longValue();
        LinkedHashSet<Long> recipients = new LinkedHashSet<>();
        Long testOwnerId = view.get("test_owner_id") == null
                ? null : ((Number) view.get("test_owner_id")).longValue();
        if (testOwnerId != null) {
            recipients.add(testOwnerId);
        } else {
            recipients.addAll(projectOwners.resolve(projectId));
        }
        if (recipients.isEmpty()) {
            log.warn("批次 {} 完成通知无收件人（test_owner 未设且无项目负责人）", batchId);
            return;
        }

        String batchLabel = view.get("name") == null ? "#" + batchId : String.valueOf(view.get("name"));
        String projectLabel = view.get("project_name") == null
                ? "项目" + projectId : "项目「" + view.get("project_name") + "」";
        int total = num(view.get("total"));
        int pass = num(view.get("pass_cnt"));
        int fail = num(view.get("fail_cnt"));
        int timeout = num(view.get("timeout_cnt"));
        int skip = num(view.get("skip_cnt"));
        BigDecimal passRate = view.get("pass_rate") == null
                ? null : new BigDecimal(String.valueOf(view.get("pass_rate")));
        String ratePhrase = BadgeRatePhrase.of(passRate);
        String title = "批次执行完成：" + batchLabel + "（" + ratePhrase + "）";
        String content = projectLabel + "批次「" + batchLabel + "」全部任务已执行完毕："
                + "共 " + total + " 条，成功 " + pass + "、失败 " + fail
                + "、超时 " + timeout + "、跳过 " + skip + "，" + ratePhrase
                + "。可前往批次详情查看汇总报告。";

        afterCommitExecutor.execute(() -> {
            for (Long userId : recipients) {
                try {
                    notificationService.create(new NotificationCreateInput(userId, projectId,
                            "BATCH_FINISHED", title, content,
                            "BATCH_FINISHED:" + batchId + ":" + userId));
                } catch (Exception e) {
                    log.warn("BATCH_FINISHED 通知失败（batchId={} user={}）: {}",
                            batchId, userId, e.getMessage());
                }
            }
            if (targetStatus == BATCH_DONE && batchNotifySwitch.feishuEnabled(batchId)) {
                try {
                    feishuWebhookChannel.sendBatchNotice(batchId);
                } catch (Exception e) {
                    log.warn("BATCH_FINISHED 飞书群通知异常已兜底（batchId={}）: {}", batchId, e.getMessage());
                }
            }
        });
    }

    private static int num(Object v) {
        return v == null ? 0 : ((Number) v).intValue();
    }
}
