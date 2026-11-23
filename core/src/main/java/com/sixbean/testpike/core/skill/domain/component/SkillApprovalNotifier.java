package com.sixbean.testpike.core.skill.domain.component;

import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.component.ApprovalNotifierSupport;
import com.sixbean.testpike.core.skill.api.dto.SkillInfo;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;


@Component
public class SkillApprovalNotifier {

    private final ApprovalNotifierSupport support;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ProjectOwners projectOwners;

    public SkillApprovalNotifier(ApprovalNotifierSupport support,
                                 AdminAlertRecipients adminAlertRecipients,
                                 ProjectOwners projectOwners) {
        this.support = support;
        this.adminAlertRecipients = adminAlertRecipients;
        this.projectOwners = projectOwners;
    }

    
    public void notifySubmitted(Long operatorId, SkillInfo after, boolean republish) {
        String scope = support.projectLabel(after.projectId());
        String action = republish ? "内容改版，自动重新进入审批" : "已提交审批";
        String title = "SKILL 待审批：" + after.name();
        String content = scope + "SKILL「" + after.name() + "」" + action
                + "（当前版本 v" + after.currentVersion() + "，提交人：" + support.displayName(operatorId)
                + "）。请前往 SKILL 管理页审批处理。";
        LinkedHashSet<Long> recipients = new LinkedHashSet<>(adminAlertRecipients.adminUserIds());
        if (after.projectId() != null) {
            recipients.addAll(projectOwners.resolve(after.projectId()));
        }
        support.send("SKILL_APPROVAL_REQUEST", after.projectId(), recipients, title, content,
                "SKILL_APPROVAL_REQUEST:" + after.id() + ":" + after.version());
    }

    
    public void notifyApproved(Long operatorId, SkillInfo after) {
        String title = "SKILL 审批通过：" + after.name();
        String content = "你提交的 " + support.projectLabel(after.projectId()) + "SKILL「" + after.name()
                + "」v" + after.currentVersion() + " 已审批通过并发布（审批人："
                + support.displayName(operatorId) + "）。";
        support.send("SKILL_APPROVED", after.projectId(), List.of(after.createdBy()), title, content,
                "SKILL_APPROVED:" + after.id() + ":" + after.version());
    }

 
    public void notifyRejected(Long operatorId, SkillInfo after) {
        String title = "SKILL 审批拒绝：" + after.name();
        String content = "你提交的 " + support.projectLabel(after.projectId()) + "SKILL「" + after.name()
                + "」v" + after.currentVersion() + " 已被拒绝（审批人：" + support.displayName(operatorId)
                + "）。原因：" + after.rejectReason() + "。修改后可重新提交审批。";
        support.send("SKILL_APPROVAL_REJECTED", after.projectId(), List.of(after.createdBy()),
                title, content,
                "SKILL_APPROVAL_REJECTED:" + after.id() + ":" + after.version());
    }
}
