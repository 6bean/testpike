package com.sixbean.testpike.core.delreq.domain.component;

import com.sixbean.testpike.core.common.domain.component.ProjectOwners;
import com.sixbean.testpike.core.delreq.api.dto.DeleteRequestInfo;
import com.sixbean.testpike.core.notification.domain.component.AdminAlertRecipients;
import com.sixbean.testpike.core.notification.domain.component.ApprovalNotifierSupport;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;


@Component
public class DeleteRequestApprovalNotifier {

    private final ApprovalNotifierSupport support;
    private final AdminAlertRecipients adminAlertRecipients;
    private final ProjectOwners projectOwners;

    public DeleteRequestApprovalNotifier(ApprovalNotifierSupport support,
                                         AdminAlertRecipients adminAlertRecipients,
                                         ProjectOwners projectOwners) {
        this.support = support;
        this.adminAlertRecipients = adminAlertRecipients;
        this.projectOwners = projectOwners;
    }

    
    public void notifySubmitted(DeleteRequestInfo after) {
        String title = "删除申请待审批：" + after.targetSummary();
        String content = support.projectLabel(after.projectId()) + "的"
                + after.targetTypeLabel() + "「" + after.targetSummary() + "」被提交删除申请"
                + "（申请人：" + support.displayName(after.applicantId())
                + "，理由：" + after.reason() + "）。审批通过后该对象将软删进入回收站，请前往删除审批页处理。";
        LinkedHashSet<Long> recipients = new LinkedHashSet<>(adminAlertRecipients.adminUserIds());
        recipients.addAll(projectOwners.resolve(after.projectId()));
        support.send("DELETE_APPROVAL_REQUEST", after.projectId(), recipients, title, content,
                "DELETE_APPROVAL_REQUEST:" + after.id() + ":" + after.status());
    }

 
    public void notifyApproved(Long operatorId, DeleteRequestInfo after) {
        String title = "删除申请已通过：" + after.targetSummary();
        String content = "你提交的" + support.projectLabel(after.projectId()) + after.targetTypeLabel()
                + "「" + after.targetSummary() + "」删除申请已审批通过（审批人："
                + support.displayName(operatorId) + "），对象已软删并进入回收站（30 天内可恢复）。";
        support.send("DELETE_APPROVED", after.projectId(), List.of(after.applicantId()), title, content,
                "DELETE_APPROVED:" + after.id() + ":" + after.status());
    }

    
    public void notifyRejected(Long operatorId, DeleteRequestInfo after) {
        String title = "删除申请被拒绝：" + after.targetSummary();
        String content = "你提交的" + support.projectLabel(after.projectId()) + after.targetTypeLabel()
                + "「" + after.targetSummary() + "」删除申请已被拒绝（审批人："
                + support.displayName(operatorId) + "）。原因：" + after.rejectReason()
                + "。对象未做任何删除处理。";
        support.send("DELETE_APPROVAL_REJECTED", after.projectId(), List.of(after.applicantId()),
                title, content,
                "DELETE_APPROVAL_REJECTED:" + after.id() + ":" + after.status());
    }
}
