package com.sixbean.testpike.core.tool.domain.component;

import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.component.ApprovalNotifierSupport;
import com.sixbean.testpike.core.tool.api.dto.ToolInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;


@Component
public class ToolApprovalNotifier {

    private final ApprovalNotifierSupport support;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ProjectOwners projectOwners;

    public ToolApprovalNotifier(ApprovalNotifierSupport support,
                                AdminAlertRecipients adminAlertRecipients,
                                ProjectOwners projectOwners) {
        this.support = support;
        this.adminAlertRecipients = adminAlertRecipients;
        this.projectOwners = projectOwners;
    }

    
    public void notifySubmitted(Long operatorId, ToolInfo after, boolean republish) {
        String scope = support.projectLabel(after.projectId());
        String action = republish ? "内容改版，自动重新进入审批" : "已提交审批";
        String title = "工具待审批：" + after.name();
        String content = scope + "工具「" + after.name() + "」" + action
                + "（当前版本 v" + after.currentVersion() + "，提交人：" + support.displayName(operatorId)
                + "）。请前往工具管理页审批处理。";
        LinkedHashSet<Long> recipients = new LinkedHashSet<>(adminAlertRecipients.adminUserIds());
        if (after.projectId() != null) {
            recipients.addAll(projectOwners.resolve(after.projectId()));
        }
        support.send("TOOL_APPROVAL_REQUEST", after.projectId(), recipients, title, content,
                "TOOL_APPROVAL_REQUEST:" + after.id() + ":" + after.version());
    }

    
    public void notifyApproved(Long operatorId, ToolInfo after) {
        String title = "工具审批通过：" + after.name();
        String content = "你提交的 " + support.projectLabel(after.projectId()) + "工具「" + after.name()
                + "」v" + after.currentVersion() + " 已审批通过并发布（审批人："
                + support.displayName(operatorId) + "）。";
        support.send("TOOL_APPROVED", after.projectId(), List.of(after.createdBy()), title, content,
                "TOOL_APPROVED:" + after.id() + ":" + after.version());
    }

    
    public void notifyRejected(Long operatorId, ToolInfo after) {
        String title = "工具审批拒绝：" + after.name();
        String content = "你提交的 " + support.projectLabel(after.projectId()) + "工具「" + after.name()
                + "」v" + after.currentVersion() + " 已被拒绝（审批人：" + support.displayName(operatorId)
                + "）。原因：" + after.rejectReason() + "。修改后可重新提交审批。";
        support.send("TOOL_APPROVAL_REJECTED", after.projectId(), List.of(after.createdBy()),
                title, content,
                "TOOL_APPROVAL_REJECTED:" + after.id() + ":" + after.version());
    }
}
